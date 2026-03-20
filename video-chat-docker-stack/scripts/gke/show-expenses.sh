#!/usr/bin/env bash
set -euo pipefail

# Query a Cloud Billing export in BigQuery for the actual spend of the current
# GKE project. The script prefers explicit env config and falls back to light
# discovery when those billing export coordinates are not set yet.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command bq
require_command rg

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/show-expenses.sh [options]

Show actual Google Cloud spend for the configured project using a Cloud Billing
export in BigQuery.

By default, the script reports spend from the GCP project creation time until
now. It prints:
- total gross cost
- total credits
- total net cost
- daily net cost
- top services by net cost

Options:
  --env <path>              Use a specific env file instead of k8s/k8s.env
  --from <timestamp>        Override the start time (RFC3339 or YYYY-MM-DD)
  --billing-project <id>    BigQuery project that contains the billing export
  --dataset <name>          BigQuery dataset that contains the billing export
  --table <name>            BigQuery table name to query
  --top <count>             Number of services to show (default: 10)
  --help                    Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
START_TIME=""
BILLING_EXPORT_PROJECT=""
BILLING_EXPORT_DATASET=""
BILLING_EXPORT_TABLE=""
TOP_COUNT=10

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --from)
      [[ $# -ge 2 ]] || fail "--from requires a timestamp"
      START_TIME="$2"
      shift 2
      ;;
    --billing-project)
      [[ $# -ge 2 ]] || fail "--billing-project requires a project ID"
      BILLING_EXPORT_PROJECT="$2"
      shift 2
      ;;
    --dataset)
      [[ $# -ge 2 ]] || fail "--dataset requires a dataset name"
      BILLING_EXPORT_DATASET="$2"
      shift 2
      ;;
    --table)
      [[ $# -ge 2 ]] || fail "--table requires a table name"
      BILLING_EXPORT_TABLE="$2"
      shift 2
      ;;
    --top)
      [[ $# -ge 2 ]] || fail "--top requires a count"
      TOP_COUNT="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

project_create_time() {
  gcloud projects describe "${GCP_PROJECT_ID}" --format='value(createTime)' | tr -d '\r'
}

# Discovery stays intentionally simple: scan visible projects and datasets for
# the standard billing-export naming patterns Google creates.
dataset_ids_for_project() {
  local project_id="$1"
  local json_output

  json_output="$(bq ls --project_id="${project_id}" --format=prettyjson 2>/dev/null || true)"
  printf '%s\n' "${json_output}" \
    | rg -o '"datasetId":\s*"[^"]+"' \
    | sed -E 's/.*"([^"]+)"/\1/'
}

first_matching_table() {
  local project_id="$1"
  local dataset_id="$2"
  local pattern="$3"
  local json_output

  json_output="$(bq ls --project_id="${project_id}" --max_results=1000 --format=prettyjson "${project_id}:${dataset_id}" 2>/dev/null || true)"
  printf '%s\n' "${json_output}" \
    | rg -o "\"tableId\":\\s*\"${pattern}[^\"]*\"" \
    | sed -E 's/.*"([^"]+)"/\1/' \
    | head -n 1
}

discover_billing_export() {
  local project_id dataset_id table_name

  while IFS= read -r project_id; do
    [[ -n "${project_id}" ]] || continue

    while IFS= read -r dataset_id; do
      [[ -n "${dataset_id}" ]] || continue

      table_name="$(first_matching_table "${project_id}" "${dataset_id}" 'gcp_billing_export_v1_')"
      if [[ -n "${table_name}" ]]; then
        BILLING_EXPORT_PROJECT="${project_id}"
        BILLING_EXPORT_DATASET="${dataset_id}"
        BILLING_EXPORT_TABLE="${table_name}"
        return 0
      fi

      table_name="$(first_matching_table "${project_id}" "${dataset_id}" 'gcp_billing_export_resource_v1_')"
      if [[ -n "${table_name}" ]]; then
        BILLING_EXPORT_PROJECT="${project_id}"
        BILLING_EXPORT_DATASET="${dataset_id}"
        BILLING_EXPORT_TABLE="${table_name}"
        return 0
      fi
    done < <(dataset_ids_for_project "${project_id}")
  done < <(gcloud projects list --format='value(projectId)' 2>/dev/null)

  return 1
}

# Build one reusable SQL wrapper so the summary, daily costs, and service
# breakdown all operate on the same filtered project/time window.
render_query() {
  local select_sql="$1"

  cat <<EOF
WITH project_costs AS (
  SELECT
    usage_start_time,
    COALESCE(service.description, 'Unknown') AS service_description,
    cost,
    currency,
    IFNULL((SELECT SUM(credit.amount) FROM UNNEST(credits) AS credit), 0) AS credits_total
  FROM \`${BILLING_EXPORT_PROJECT}.${BILLING_EXPORT_DATASET}.${BILLING_EXPORT_TABLE}\`
  WHERE project.id = @target_project
    AND usage_start_time >= @start_time
)
${select_sql}
EOF
}

run_query() {
  local query_sql="$1"

  bq query \
    --use_legacy_sql=false \
    --format=pretty \
    --parameter="target_project::${GCP_PROJECT_ID}" \
    --parameter="start_time:TIMESTAMP:${START_TIME}" \
    "${query_sql}"
}

load_k8s_env "${ENV_FILE}"

if [[ -z "${START_TIME}" ]]; then
  START_TIME="$(project_create_time)"
fi

if [[ -z "${BILLING_EXPORT_PROJECT}" ]]; then
  BILLING_EXPORT_PROJECT="$(trim_cr "$(read_env_value "${ENV_FILE}" "GCP_BILLING_EXPORT_PROJECT")")"
fi
if [[ -z "${BILLING_EXPORT_DATASET}" ]]; then
  BILLING_EXPORT_DATASET="$(trim_cr "$(read_env_value "${ENV_FILE}" "GCP_BILLING_EXPORT_DATASET")")"
fi
if [[ -z "${BILLING_EXPORT_TABLE}" ]]; then
  BILLING_EXPORT_TABLE="$(trim_cr "$(read_env_value "${ENV_FILE}" "GCP_BILLING_EXPORT_TABLE")")"
fi

if [[ -z "${BILLING_EXPORT_PROJECT}" || -z "${BILLING_EXPORT_DATASET}" || -z "${BILLING_EXPORT_TABLE}" ]]; then
  discover_billing_export || true
fi

# Fail with concrete setup instructions instead of returning an empty report when
# billing export has not been enabled yet.
if [[ -z "${BILLING_EXPORT_PROJECT}" || -z "${BILLING_EXPORT_DATASET}" || -z "${BILLING_EXPORT_TABLE}" ]]; then
  cat >&2 <<EOF
No Cloud Billing export table was found automatically.

To use this script, enable Cloud Billing export to BigQuery and then set these
values in ${ENV_FILE}:
- GCP_BILLING_EXPORT_PROJECT
- GCP_BILLING_EXPORT_DATASET
- GCP_BILLING_EXPORT_TABLE

Recommended export type:
- Standard usage cost export: gcp_billing_export_v1_<BILLING_ACCOUNT_ID>
- Detailed usage cost export: gcp_billing_export_resource_v1_<BILLING_ACCOUNT_ID>
EOF
  exit 1
fi

cat <<EOF
Billing export source: ${BILLING_EXPORT_PROJECT}.${BILLING_EXPORT_DATASET}.${BILLING_EXPORT_TABLE}
Target project: ${GCP_PROJECT_ID}
Window start: ${START_TIME}
Window end: now
EOF
printf '\n'

echo "Summary"
run_query "$(render_query '
SELECT
  STRING_AGG(DISTINCT currency, \", \") AS currencies,
  ROUND(SUM(cost), 2) AS gross_cost,
  ROUND(SUM(credits_total), 2) AS credits,
  ROUND(SUM(cost) + SUM(credits_total), 2) AS net_cost
FROM project_costs
')"
printf '\n'

echo "Daily Net Cost"
run_query "$(render_query '
SELECT
  DATE(usage_start_time) AS usage_date,
  ROUND(SUM(cost) + SUM(credits_total), 2) AS net_cost
FROM project_costs
GROUP BY usage_date
ORDER BY usage_date DESC
')"
printf '\n'

echo "Top Services"
run_query "$(render_query "
SELECT
  service_description AS service,
  ROUND(SUM(cost) + SUM(credits_total), 2) AS net_cost
FROM project_costs
GROUP BY service
ORDER BY net_cost DESC, service ASC
LIMIT ${TOP_COUNT}
")"

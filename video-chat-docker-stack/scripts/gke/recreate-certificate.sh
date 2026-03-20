#!/usr/bin/env bash
set -euo pipefail

# Rotate the managed certificate by creating a new resource, switching the
# Gateway to it, and then removing the old resource when GCP releases it.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command kubectl
require_command python3

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/recreate-certificate.sh [options]

Rotate the Google-managed SSL certificate configured for the GKE Gateway.

What it does:
- creates a new managed certificate resource for K8S_HOST
- updates k8s/k8s.env to use the new certificate name
- patches the live Gateway to reference the new certificate
- optionally waits until the new certificate reaches ACTIVE
- removes the old certificate after GCP releases it from the HTTPS proxy

Options:
  --env <path>         Use a specific env file instead of k8s/k8s.env
  --name <value>       Explicit name for the new managed certificate
  --wait               Poll until the new certificate becomes ACTIVE
  --timeout <seconds>  Maximum wait time for wait/delete steps (default: 1800)
  --interval <seconds> Poll interval for wait/delete steps (default: 30)
  --yes                Confirm the managed certificate should be rotated
  --verbose            Show narrated step-by-step output (default)
  --quiet              Reduce output to command errors and the final summary
  --help               Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
NEW_CERTIFICATE_NAME=""
WAIT_FOR_ACTIVE=false
TIMEOUT_SEC=1800
INTERVAL_SEC=30
CONFIRMED=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --name)
      [[ $# -ge 2 ]] || fail "--name requires a value"
      NEW_CERTIFICATE_NAME="$2"
      shift 2
      ;;
    --wait)
      WAIT_FOR_ACTIVE=true
      shift
      ;;
    --timeout)
      [[ $# -ge 2 ]] || fail "--timeout requires a value"
      TIMEOUT_SEC="$2"
      shift 2
      ;;
    --interval)
      [[ $# -ge 2 ]] || fail "--interval requires a value"
      INTERVAL_SEC="$2"
      shift 2
      ;;
    --yes)
      CONFIRMED=true
      shift
      ;;
    --verbose)
      set_log_level verbose
      shift
      ;;
    --quiet)
      set_log_level quiet
      shift
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

load_k8s_env "${ENV_FILE}"

if [[ "${K8S_EXPOSURE_MODE}" != "gke-gateway" ]]; then
  fail "GKE certificate rotation expects K8S_EXPOSURE_MODE=gke-gateway in ${ENV_FILE}."
fi

if [[ "${CONFIRMED}" != "true" ]]; then
  cat >&2 <<EOF
This will rotate the managed certificate referenced by the Gateway:
  current: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}

Use --yes to confirm.
EOF
  exit 1
fi

timestamp_suffix() {
  date -u +%Y%m%d%H%M%S
}

derive_certificate_name() {
  local base suffix candidate max_base_length

  suffix="-$(timestamp_suffix)"
  base="${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}"
  max_base_length=$((63 - ${#suffix}))
  candidate="${base:0:${max_base_length}}${suffix}"
  printf '%s\n' "${candidate}"
}

certificate_exists() {
  local certificate_name="$1"
  gcloud compute ssl-certificates describe \
    "${certificate_name}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

certificate_state_json() {
  local certificate_name="$1"
  gcloud compute ssl-certificates describe \
    "${certificate_name}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format=json 2>/dev/null
}

create_certificate() {
  local certificate_name="$1"
  if certificate_exists "${certificate_name}"; then
    log_info "Managed certificate ${certificate_name} already exists."
    return 0
  fi

  log_info "Creating managed certificate ${certificate_name} for ${K8S_HOST}."
  run_with_log_mode gcloud compute ssl-certificates create \
    "${certificate_name}" \
    --domains "${K8S_HOST}" \
    --global \
    --project "${GCP_PROJECT_ID}"
}

delete_certificate() {
  local certificate_name="$1"
  if ! certificate_exists "${certificate_name}"; then
    log_info "Managed certificate ${certificate_name} does not exist."
    return 0
  fi

  run_with_log_mode gcloud compute ssl-certificates delete \
    "${certificate_name}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

https_listener_index() {
  local gateway_json

  gateway_json="$(kubectl get gateway "${K8S_GKE_GATEWAY_NAME}" --namespace "${K8S_NAMESPACE}" -o json)"
  GATEWAY_JSON="${gateway_json}" python3 - <<'PY'
import json
import os
import sys

data = json.loads(os.environ["GATEWAY_JSON"])
listeners = data.get("spec", {}).get("listeners", [])
for index, listener in enumerate(listeners):
    if listener.get("name") == "https":
        print(index)
        sys.exit(0)
sys.exit(1)
PY
}

current_gateway_certificate() {
  kubectl get gateway "${K8S_GKE_GATEWAY_NAME}" \
    --namespace "${K8S_NAMESPACE}" \
    -o jsonpath='{.spec.listeners[?(@.name=="https")].tls.options.networking\.gke\.io/pre-shared-certs}'
}

patch_gateway_certificate() {
  local certificate_name="$1"
  local listener_index patch

  listener_index="$(https_listener_index)" || fail "Could not locate the https listener on Gateway ${K8S_GKE_GATEWAY_NAME}."
  patch="$(printf '[{"op":"replace","path":"/spec/listeners/%s/tls/options/networking.gke.io~1pre-shared-certs","value":"%s"}]' "${listener_index}" "${certificate_name}")"

  log_info "Patching Gateway ${K8S_GKE_GATEWAY_NAME} to use managed certificate ${certificate_name}."
  run_with_log_mode kubectl patch gateway "${K8S_GKE_GATEWAY_NAME}" \
    --namespace "${K8S_NAMESPACE}" \
    --type json \
    -p "${patch}"
}

wait_for_gateway_certificate_reference() {
  local expected_name="$1"
  local deadline current_name

  deadline=$((SECONDS + TIMEOUT_SEC))
  while (( SECONDS < deadline )); do
    current_name="$(current_gateway_certificate)"
    if [[ "${current_name}" == "${expected_name}" ]]; then
      return 0
    fi

    sleep "${INTERVAL_SEC}"
  done

  fail "Gateway ${K8S_GKE_GATEWAY_NAME} did not switch to managed certificate ${expected_name} within ${TIMEOUT_SEC}s."
}

wait_for_certificate_active() {
  local certificate_name="$1"
  local deadline status_json status_output managed_status domain_status

  deadline=$((SECONDS + TIMEOUT_SEC))
  while (( SECONDS < deadline )); do
    status_json="$(certificate_state_json "${certificate_name}")"
    status_output="$(
      CERT_JSON="${status_json}" python3 - "${K8S_HOST}" <<'PY'
import json
import os
import sys

data = json.loads(os.environ["CERT_JSON"])
managed = data.get("managed", {})
host = sys.argv[1]

print(managed.get("status", ""))
print(managed.get("domainStatus", {}).get(host, ""))
PY
    )"

    managed_status="$(printf '%s\n' "${status_output}" | sed -n '1p')"
    domain_status="$(printf '%s\n' "${status_output}" | sed -n '2p')"

    cat <<EOF
Certificate: ${certificate_name}
Host: ${K8S_HOST}
Managed status: ${managed_status:-<unknown>}
Domain status: ${domain_status:-<unknown>}
EOF

    if [[ "${managed_status}" == "ACTIVE" && "${domain_status}" == "ACTIVE" ]]; then
      return 0
    fi

    sleep "${INTERVAL_SEC}"
  done

  fail "Managed certificate ${certificate_name} is still not ACTIVE after ${TIMEOUT_SEC}s."
}

wait_for_old_certificate_release() {
  local old_certificate_name="$1"
  local deadline

  deadline=$((SECONDS + TIMEOUT_SEC))
  while (( SECONDS < deadline )); do
    if delete_certificate "${old_certificate_name}"; then
      return 0
    fi

    log_info "Old certificate ${old_certificate_name} is still attached to the HTTPS proxy; waiting..."
    sleep "${INTERVAL_SEC}"
  done

  fail "Old certificate ${old_certificate_name} is still attached after ${TIMEOUT_SEC}s."
}

GATEWAY_CERTIFICATE_NAME="$(current_gateway_certificate)"
[[ -n "${GATEWAY_CERTIFICATE_NAME}" ]] || fail "Could not determine the certificate currently referenced by Gateway ${K8S_GKE_GATEWAY_NAME}."

if [[ "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" != "${GATEWAY_CERTIFICATE_NAME}" ]]; then
  log_info "Env file currently references ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}, but the live Gateway still uses ${GATEWAY_CERTIFICATE_NAME}."
fi

OLD_CERTIFICATE_NAME="${GATEWAY_CERTIFICATE_NAME}"
if [[ -z "${NEW_CERTIFICATE_NAME}" ]]; then
  if [[ "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" != "${GATEWAY_CERTIFICATE_NAME}" && -n "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" ]]; then
    NEW_CERTIFICATE_NAME="${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}"
  else
    NEW_CERTIFICATE_NAME="$(derive_certificate_name)"
  fi
fi

if [[ "${NEW_CERTIFICATE_NAME}" == "${OLD_CERTIFICATE_NAME}" ]]; then
  fail "The new certificate name must differ from the current certificate name (${OLD_CERTIFICATE_NAME})."
fi

if [[ "${NEW_CERTIFICATE_NAME}" != "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" ]] && certificate_exists "${NEW_CERTIFICATE_NAME}"; then
  fail "Managed certificate ${NEW_CERTIFICATE_NAME} already exists. Pick a different name with --name."
fi

log_step "Loading certificate rotation configuration"
log_info "Env file: ${ENV_FILE}"
log_info "Project: ${GCP_PROJECT_ID}"
log_info "Host: ${K8S_HOST}"
log_info "Current certificate: ${OLD_CERTIFICATE_NAME}"
log_info "New certificate: ${NEW_CERTIFICATE_NAME}"

log_step "Creating replacement managed certificate"
create_certificate "${NEW_CERTIFICATE_NAME}"

log_step "Switching the live Gateway to the new certificate"
patch_gateway_certificate "${NEW_CERTIFICATE_NAME}"

log_step "Waiting for the Gateway spec to reference the new certificate"
wait_for_gateway_certificate_reference "${NEW_CERTIFICATE_NAME}"

log_step "Persisting the new certificate name to the env file"
write_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME" "${NEW_CERTIFICATE_NAME}"

if [[ "${WAIT_FOR_ACTIVE}" == "true" ]]; then
  log_step "Waiting for the new managed certificate to become ACTIVE"
  wait_for_certificate_active "${NEW_CERTIFICATE_NAME}"
else
  log_info "Use ./scripts/gke/check-certificate.sh or ./scripts/gke/check-public-endpoint.sh to monitor the new certificate."
fi

log_step "Removing the old managed certificate"
wait_for_old_certificate_release "${OLD_CERTIFICATE_NAME}"

cat <<EOF
Managed certificate rotated.

Old certificate: ${OLD_CERTIFICATE_NAME}
New certificate: ${NEW_CERTIFICATE_NAME}
Host: ${K8S_HOST}
EOF

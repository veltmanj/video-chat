#!/usr/bin/env bash
set -euo pipefail

# Recreate the GKE cluster while preserving the reusable Google Cloud edge
# resources that bootstrap.sh manages, such as the static IP, certificate, DNS,
# Artifact Registry repository, and the local env file.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/recreate.sh [options]

Recreate the GKE cluster for this stack while preserving the reusable GCP
resources created by bootstrap.sh.

What it does:
- deletes the in-cluster namespace if the cluster still exists
- deletes the GKE cluster itself
- reruns scripts/gke/bootstrap.sh with the current env file and optional sizing overrides

What it preserves:
- the Google Cloud project
- Artifact Registry repository
- global static IP
- Google-managed SSL certificate
- Cloud DNS record
- local k8s env file

Options:
  --env <path>             Use a specific env file instead of k8s/k8s.env
  --cluster-location <id>  Override GKE_CLUSTER_LOCATION and persist it to the env file
  --machine-type <type>    Override GKE_MACHINE_TYPE and persist it to the env file
  --node-count <count>     Override GKE_NODE_COUNT and persist it to the env file
  --release-channel <id>   Override GKE_RELEASE_CHANNEL and persist it to the env file
  --skip-build             Reuse the configured image tag instead of rebuilding
  --skip-dns-update        Do not create or update the Cloud DNS A record during bootstrap
  --verbose                Show narrated step-by-step output (default)
  --quiet                  Reduce output to command errors and the final summary
  --yes                    Required acknowledgement for this destructive operation
  --help                   Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
CLUSTER_LOCATION_OVERRIDE=""
MACHINE_TYPE_OVERRIDE=""
NODE_COUNT_OVERRIDE=""
RELEASE_CHANNEL_OVERRIDE=""
SKIP_BUILD=false
SKIP_DNS_UPDATE=false
CONFIRMED=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --cluster-location)
      [[ $# -ge 2 ]] || fail "--cluster-location requires a value"
      CLUSTER_LOCATION_OVERRIDE="$2"
      shift 2
      ;;
    --machine-type)
      [[ $# -ge 2 ]] || fail "--machine-type requires a value"
      MACHINE_TYPE_OVERRIDE="$2"
      shift 2
      ;;
    --node-count)
      [[ $# -ge 2 ]] || fail "--node-count requires a value"
      NODE_COUNT_OVERRIDE="$2"
      shift 2
      ;;
    --release-channel)
      [[ $# -ge 2 ]] || fail "--release-channel requires a value"
      RELEASE_CHANNEL_OVERRIDE="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-dns-update)
      SKIP_DNS_UPDATE=true
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
    --yes)
      CONFIRMED=true
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
  fail "GKE recreate expects K8S_EXPOSURE_MODE=gke-gateway in ${ENV_FILE}."
fi

cluster_exists() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

configure_kubectl_context() {
  log_info "Fetching kubectl credentials for ${GKE_CLUSTER_NAME}."
  run_with_log_mode gcloud container clusters get-credentials \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}"
}

run_k8s_teardown() {
  local kube_context

  cluster_exists || {
    log_skip "Kubernetes namespace teardown because cluster ${GKE_CLUSTER_NAME} does not exist."
    return
  }

  configure_kubectl_context
  kube_context="$(current_kube_context)"
  [[ -n "${kube_context}" ]] || fail "Unable to determine kubectl context for GKE cluster ${GKE_CLUSTER_NAME}."

  log_info "Running Kubernetes teardown for namespace ${K8S_NAMESPACE} on context ${kube_context}."
  VIDEOCHAT_KUBECTL_CONTEXT="${kube_context}" run_with_log_mode "${K8S_SCRIPT_DIR}/teardown.sh" --env "${ENV_FILE}" "--${LOG_LEVEL}"
}

delete_cluster() {
  cluster_exists || {
    log_info "Cluster ${GKE_CLUSTER_NAME} does not exist."
    return
  }

  log_info "Deleting GKE cluster ${GKE_CLUSTER_NAME} in ${GKE_CLUSTER_LOCATION}."
  run_with_log_mode gcloud container clusters delete \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

run_bootstrap() {
  local args

  args=(--env "${ENV_FILE}" "--${LOG_LEVEL}")
  [[ -n "${CLUSTER_LOCATION_OVERRIDE}" ]] && args+=(--cluster-location "${CLUSTER_LOCATION_OVERRIDE}")
  [[ -n "${MACHINE_TYPE_OVERRIDE}" ]] && args+=(--machine-type "${MACHINE_TYPE_OVERRIDE}")
  [[ -n "${NODE_COUNT_OVERRIDE}" ]] && args+=(--node-count "${NODE_COUNT_OVERRIDE}")
  [[ -n "${RELEASE_CHANNEL_OVERRIDE}" ]] && args+=(--release-channel "${RELEASE_CHANNEL_OVERRIDE}")
  [[ "${SKIP_BUILD}" == "true" ]] && args+=(--skip-build)
  [[ "${SKIP_DNS_UPDATE}" == "true" ]] && args+=(--skip-dns-update)

  log_info "Re-running bootstrap with the preserved project and edge resources."
  run_with_log_mode "${SCRIPT_DIR}/bootstrap.sh" "${args[@]}"
}

if [[ "${CONFIRMED}" != "true" ]]; then
  cat >&2 <<EOF
This operation is destructive.

It will delete the Kubernetes namespace and the GKE cluster for:
- Project: ${GCP_PROJECT_ID}
- Cluster: ${GKE_CLUSTER_NAME} (${GKE_CLUSTER_LOCATION})
- Namespace: ${K8S_NAMESPACE}

Reusable edge resources such as the static IP, certificate, DNS record, and
Artifact Registry repository are preserved.

Re-run with --yes to continue.
EOF
  exit 1
fi

log_step "Loading recreate configuration"
log_info "Env file: ${ENV_FILE}"
log_info "Project: ${GCP_PROJECT_ID}"
log_info "Cluster: ${GKE_CLUSTER_NAME} (${GKE_CLUSTER_LOCATION})"
log_info "Namespace: ${K8S_NAMESPACE}"
log_info "Host: ${K8S_HOST}"
[[ -n "${MACHINE_TYPE_OVERRIDE}" ]] && log_info "Requested machine type override: ${MACHINE_TYPE_OVERRIDE}"
[[ -n "${NODE_COUNT_OVERRIDE}" ]] && log_info "Requested node count override: ${NODE_COUNT_OVERRIDE}"
[[ -n "${CLUSTER_LOCATION_OVERRIDE}" ]] && log_info "Requested cluster location override: ${CLUSTER_LOCATION_OVERRIDE}"
[[ -n "${RELEASE_CHANNEL_OVERRIDE}" ]] && log_info "Requested release channel override: ${RELEASE_CHANNEL_OVERRIDE}"

log_step "Removing in-cluster application resources"
run_k8s_teardown

log_step "Deleting the GKE cluster"
delete_cluster

log_step "Recreating the cluster and redeploying the stack"
run_bootstrap

cat <<EOF
GKE recreate complete.

Project: ${GCP_PROJECT_ID}
Cluster: ${GKE_CLUSTER_NAME}
Host: https://${K8S_HOST}/

Preserved resources:
- Artifact Registry repository
- Global static IP
- Managed SSL certificate
- Cloud DNS record
- Local env file
EOF

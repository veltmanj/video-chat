#!/usr/bin/env bash
set -euo pipefail

# Reverse the public-cloud resources layered on top of the Kubernetes bundle.
# The script deletes the namespace first, then tears down the optional GCP
# resources that bootstrap.sh may have created.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/teardown.sh [options]

Tear down the GKE deployment and the public Google Cloud resources created for it.

Default behavior:
- deletes the Kubernetes namespace through scripts/k8s/teardown.sh
- removes the Google-managed SSL certificate configured for the Gateway
- releases the reserved global static IP configured for the Gateway

Optional behavior:
- removes the Cloud DNS A record when a managed zone is configured
- deletes the GKE cluster
- deletes the Artifact Registry repository
- deletes the local env file and rendered manifests

Options:
  --env <path>                    Use a specific env file instead of k8s/k8s.env
  --delete-dns-record             Remove the A record for K8S_HOST from GCP_DNS_MANAGED_ZONE
  --delete-cluster                Delete the configured GKE cluster after namespace teardown
  --delete-artifact-repository    Delete the configured Artifact Registry repository
  --delete-env-file               Remove the local k8s env file after teardown
  --delete-rendered               Remove k8s/rendered after teardown
  --verbose                       Show narrated step-by-step output (default)
  --quiet                         Reduce output to command errors and the final summary
  --help                          Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
DELETE_DNS_RECORD=false
DELETE_CLUSTER=false
DELETE_ARTIFACT_REPOSITORY=false
DELETE_ENV_FILE=false
DELETE_RENDERED=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --delete-dns-record)
      DELETE_DNS_RECORD=true
      shift
      ;;
    --delete-cluster)
      DELETE_CLUSTER=true
      shift
      ;;
    --delete-artifact-repository)
      DELETE_ARTIFACT_REPOSITORY=true
      shift
      ;;
    --delete-env-file)
      DELETE_ENV_FILE=true
      shift
      ;;
    --delete-rendered)
      DELETE_RENDERED=true
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
  fail "GKE teardown expects K8S_EXPOSURE_MODE=gke-gateway in ${ENV_FILE}."
fi

cluster_exists() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

# Rebuild kubectl auth from gcloud state so teardown works on a fresh shell too.
configure_kubectl_context() {
  log_info "Fetching kubectl credentials for ${GKE_CLUSTER_NAME}."
  run_with_log_mode gcloud container clusters get-credentials \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}"
}

dns_record_exists() {
  local fqdn

  fqdn="${K8S_HOST%.}."
  [[ -n "${GCP_DNS_MANAGED_ZONE:-}" ]] || return 1

  gcloud dns record-sets list \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" \
    --name "${fqdn}" \
    --type A \
    --format='value(rrdatas)' | grep -q .
}

delete_dns_record() {
  local fqdn records

  if [[ "${DELETE_DNS_RECORD}" != "true" ]]; then
    log_skip "DNS record deletion because --delete-dns-record was not provided."
    return
  fi
  [[ -n "${GCP_DNS_MANAGED_ZONE:-}" ]] || {
    log_skip "DNS record deletion because GCP_DNS_MANAGED_ZONE is not set."
    return
  }

  fqdn="${K8S_HOST%.}."
  mapfile -t records < <(
    gcloud dns record-sets list \
      --project "${GCP_PROJECT_ID}" \
      --zone "${GCP_DNS_MANAGED_ZONE}" \
      --name "${fqdn}" \
      --type A \
      --format='value(rrdatas)'
  )

  if (( ${#records[@]} == 0 )); then
    log_info "No Cloud DNS A record found for ${fqdn}."
    return
  fi

  log_info "Removing Cloud DNS A record ${fqdn} from zone ${GCP_DNS_MANAGED_ZONE}."

  gcloud dns record-sets transaction abort \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null 2>&1 || true
  gcloud dns record-sets transaction start \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null

  for record in "${records[@]}"; do
    gcloud dns record-sets transaction remove "${record}" \
      --project "${GCP_PROJECT_ID}" \
      --zone "${GCP_DNS_MANAGED_ZONE}" \
      --name "${fqdn}" \
      --type A \
      --ttl 300 >/dev/null
  done

  run_with_log_mode gcloud dns record-sets transaction execute \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null
}

address_exists() {
  gcloud compute addresses describe \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

delete_static_ip() {
  if ! address_exists; then
    log_info "Static IP ${K8S_GKE_GATEWAY_ADDRESS_NAME} does not exist."
    return
  fi

  log_info "Deleting global static IP ${K8S_GKE_GATEWAY_ADDRESS_NAME}."
  run_with_log_mode gcloud compute addresses delete \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

certificate_exists() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

delete_ssl_certificate() {
  if ! certificate_exists; then
    log_info "Managed SSL certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME} does not exist."
    return
  fi

  log_info "Deleting managed SSL certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}."
  run_with_log_mode gcloud compute ssl-certificates delete \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

artifact_repository_exists() {
  gcloud artifacts repositories describe \
    "${GKE_ARTIFACT_REPOSITORY}" \
    --location "${GCP_REGION}" \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

delete_artifact_repository() {
  if [[ "${DELETE_ARTIFACT_REPOSITORY}" != "true" ]]; then
    log_skip "Artifact Registry repository deletion because --delete-artifact-repository was not provided."
    return
  fi
  if ! artifact_repository_exists; then
    log_info "Artifact Registry repository ${GKE_ARTIFACT_REPOSITORY} does not exist."
    return
  fi

  log_info "Deleting Artifact Registry repository ${GKE_ARTIFACT_REPOSITORY}."
  run_with_log_mode gcloud artifacts repositories delete \
    "${GKE_ARTIFACT_REPOSITORY}" \
    --location "${GCP_REGION}" \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

delete_cluster() {
  if [[ "${DELETE_CLUSTER}" != "true" ]]; then
    log_skip "cluster deletion because --delete-cluster was not provided."
    return
  fi
  if ! cluster_exists; then
    log_info "Cluster ${GKE_CLUSTER_NAME} does not exist."
    return
  fi

  log_info "Deleting GKE cluster ${GKE_CLUSTER_NAME} in ${GKE_CLUSTER_LOCATION}."
  run_with_log_mode gcloud container clusters delete \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" \
    --quiet
}

# Delegate namespace cleanup and optional rendered-manifest cleanup to the
# Kubernetes teardown script so the cluster-local logic stays in one place.
run_k8s_teardown() {
  local args kube_context

  cluster_exists || {
    log_skip "Kubernetes namespace teardown because cluster ${GKE_CLUSTER_NAME} does not exist."
    return
  }

  configure_kubectl_context
  kube_context="$(current_kube_context)"
  [[ -n "${kube_context}" ]] || fail "Unable to determine kubectl context for GKE cluster ${GKE_CLUSTER_NAME}."

  args=(--env "${ENV_FILE}")
  if [[ "${DELETE_RENDERED}" == "true" ]]; then
    args+=(--delete-rendered)
  fi

  log_info "Running Kubernetes teardown for namespace ${K8S_NAMESPACE} on context ${kube_context}."
  VIDEOCHAT_KUBECTL_CONTEXT="${kube_context}" run_with_log_mode "${K8S_SCRIPT_DIR}/teardown.sh" "${args[@]}"
}

# Teardown order matters:
# - remove in-cluster workload references first
# - then delete DNS/cert/IP/cluster/repository resources safely
log_step "Loading teardown configuration"
log_info "Env file: ${ENV_FILE}"
log_info "Project: ${GCP_PROJECT_ID}"
log_info "Cluster: ${GKE_CLUSTER_NAME} (${GKE_CLUSTER_LOCATION})"
log_info "Namespace: ${K8S_NAMESPACE}"
log_info "Host: ${K8S_HOST}"

log_step "Removing in-cluster application resources"
run_k8s_teardown

log_step "Removing optional public DNS record"
delete_dns_record

log_step "Removing managed certificate"
delete_ssl_certificate

log_step "Releasing global static IP"
delete_static_ip

log_step "Removing optional GKE cluster"
delete_cluster

log_step "Removing optional Artifact Registry repository"
delete_artifact_repository

if [[ "${DELETE_ENV_FILE}" == "true" ]]; then
  log_step "Removing local env file"
  log_info "Deleting ${ENV_FILE}."
  rm -f "${ENV_FILE}"
else
  log_skip "local env file deletion because --delete-env-file was not provided."
fi

if [[ "${DELETE_RENDERED}" == "true" ]]; then
  log_step "Removing rendered manifest cache"
  log_info "Deleting ${RENDER_DIR}."
  rm -rf "${RENDER_DIR}"
else
  log_skip "rendered manifest deletion because --delete-rendered was not provided."
fi

cat <<EOF
GKE teardown complete.

Namespace: ${K8S_NAMESPACE}
Host: ${K8S_HOST}
Static IP released: ${K8S_GKE_GATEWAY_ADDRESS_NAME}
SSL certificate removed: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}
Cluster deleted: ${DELETE_CLUSTER}
Artifact Registry repository deleted: ${DELETE_ARTIFACT_REPOSITORY}
DNS record deleted: ${DELETE_DNS_RECORD}
EOF

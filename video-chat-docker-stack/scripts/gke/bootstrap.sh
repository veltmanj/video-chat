#!/usr/bin/env bash
set -euo pipefail

# End-to-end GKE bootstrap:
# 1. normalize env defaults
# 2. provision Google Cloud prerequisites
# 3. build/push images when requested
# 4. render/apply the Kubernetes bundle

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command kubectl
require_command docker
require_command git
require_command openssl

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/bootstrap.sh [options]

Provision the Google Cloud prerequisites for the video chat stack and deploy the
current repo state to GKE through the Kubernetes bundle.

What it does:
- creates or updates k8s/k8s.env with GKE-oriented defaults
- creates the configured Google Cloud project when it does not exist yet
- enables required Google Cloud APIs
- creates a Docker Artifact Registry repository when needed
- creates or reuses a Standard GKE cluster with Gateway API enabled
- reserves a global static IP for the public HTTPS entrypoint
- creates or reuses a Google-managed SSL certificate
- optionally updates a Cloud DNS A record for K8S_HOST
- builds and pushes the three app images
- renders and applies the Kubernetes manifests in gke-gateway mode

Options:
  --env <path>             Use a specific env file instead of k8s/k8s.env
  --cluster-location <id>  Override GKE_CLUSTER_LOCATION and persist it to the env file
  --machine-type <type>    Override GKE_MACHINE_TYPE and persist it to the env file
  --node-count <count>     Override GKE_NODE_COUNT and persist it to the env file
  --release-channel <id>   Override GKE_RELEASE_CHANNEL and persist it to the env file
  --recreate-namespace     Delete the target namespace before applying
  --skip-cluster-create    Reuse an existing cluster and do not create/update it
  --skip-build             Reuse the configured image tag instead of rebuilding
  --skip-dns-update        Do not create or update the Cloud DNS A record
  --verbose                Show narrated step-by-step output (default)
  --quiet                  Reduce output to command errors and the final summary
  --help                   Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
CLUSTER_LOCATION_OVERRIDE=""
MACHINE_TYPE_OVERRIDE=""
NODE_COUNT_OVERRIDE=""
RELEASE_CHANNEL_OVERRIDE=""
RECREATE_NAMESPACE=false
SKIP_CLUSTER_CREATE=false
SKIP_BUILD=false
SKIP_DNS_UPDATE=false

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
    --recreate-namespace)
      RECREATE_NAMESPACE=true
      shift
      ;;
    --skip-cluster-create)
      SKIP_CLUSTER_CREATE=true
      shift
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
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

random_alnum() {
  local length="${1:-32}"

  openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c "${length}"
}

# Use a timestamped image tag so each bootstrap run can be rolled back and
# inspected independently in Artifact Registry and Kubernetes.
derive_image_tag() {
  local short_sha timestamp

  short_sha="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo manual)"
  timestamp="$(date -u +%Y%m%d%H%M%S)"
  printf '%s-%s\n' "${timestamp}" "${short_sha}"
}

# Rough cost heuristics keep operators aware of the order of magnitude before
# resources are created. This is intentionally not a pricing quote.
storage_gib_total() {
  local total=0

  for value in \
    "${K8S_SOCIAL_DB_STORAGE_SIZE}" \
    "${K8S_MINIO_STORAGE_SIZE}" \
    "${K8S_PROMETHEUS_STORAGE_SIZE}" \
    "${K8S_LOKI_STORAGE_SIZE}" \
    "${K8S_GRAFANA_STORAGE_SIZE}"; do
    total=$(( total + ${value%Gi} ))
  done

  printf '%s\n' "${total}"
}

machine_cost_weight() {
  case "${GKE_MACHINE_TYPE}" in
    e2-micro|e2-small|e2-medium)
      printf '1\n'
      ;;
    e2-standard-*|e2-highmem-*|e2-highcpu-*|n2d-standard-*|n2-standard-*)
      printf '2\n'
      ;;
    *)
      printf '3\n'
      ;;
  esac
}

cost_rating() {
  local node_count storage_total score

  node_count="${GKE_NODE_COUNT}"
  storage_total="$(storage_gib_total)"
  score=$(( node_count * $(machine_cost_weight) ))

  if (( storage_total >= 100 )); then
    score=$(( score + 2 ))
  elif (( storage_total >= 40 )); then
    score=$(( score + 1 ))
  fi

  if [[ "${BACKOFFICE_AI_ENABLED:-false}" == "true" ]]; then
    score=$(( score + 1 ))
  fi

  if (( score <= 3 )); then
    printf 'LOW\n'
  elif (( score <= 6 )); then
    printf 'MEDIUM\n'
  else
    printf 'HIGH\n'
  fi
}

cost_summary() {
  cat <<EOF
Cost rating: $(cost_rating)
Cost drivers:
- GKE cluster: ${GKE_NODE_COUNT} x ${GKE_MACHINE_TYPE}
- Persistent storage requested: $(storage_gib_total)Gi total
- Public edge: 1 global static IP + 1 managed HTTPS load balancer/Gateway
- Registry/storage traffic: Artifact Registry image pushes and pulls
- Monitoring stack: Prometheus, Loki, Grafana, Promtail all enabled
EOF
}

project_display_name() {
  if [[ -n "${GCP_PROJECT_NAME:-}" ]]; then
    printf '%s\n' "${GCP_PROJECT_NAME}"
    return
  fi

  printf '%s\n' "${GCP_PROJECT_ID}"
}

# Seed the env file with GKE defaults and generated secrets so subsequent
# scripts can treat k8s.env as the single source of truth.
ensure_env_file() {
  mkdir -p "$(dirname "${ENV_FILE}")"
  if [[ ! -f "${ENV_FILE}" ]]; then
    cp "${K8S_DIR}/k8s.env.example" "${ENV_FILE}"
    echo "Created ${ENV_FILE} from k8s.env.example"
  fi
}

default_project_id() {
  gcloud config get-value project 2>/dev/null | tr -d '\r'
}

default_storage_class() {
  local storage_class

  storage_class="$(kubectl get storageclass -o jsonpath='{range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{"\n"}{end}' 2>/dev/null | head -n 1)"
  if [[ -n "${storage_class}" ]]; then
    printf '%s\n' "${storage_class}"
    return
  fi

  kubectl get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true
}

storage_class_exists() {
  local name="$1"
  [[ -n "${name}" ]] || return 1
  kubectl get storageclass "${name}" >/dev/null 2>&1
}

default_storage_class_name() {
  default_storage_class
}

seed_env_defaults() {
  local project_id region artifact_repo image_registry image_tag
  local cluster_name cluster_location machine_type node_count release_channel
  local gateway_name gateway_address_name gateway_certificate_name

  ensure_env_file

  project_id="$(trim_cr "$(read_env_value "${ENV_FILE}" "GCP_PROJECT_ID")")"
  if [[ -z "${project_id}" ]]; then
    project_id="$(default_project_id)"
  fi
  [[ -n "${project_id}" ]] || fail "GCP_PROJECT_ID is empty and no default gcloud project is configured."

  region="$(trim_cr "$(read_env_value "${ENV_FILE}" "GCP_REGION")")"
  region="${region:-us-central1}"
  artifact_repo="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_ARTIFACT_REPOSITORY")")"
  artifact_repo="${artifact_repo:-videochat}"
  cluster_name="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_CLUSTER_NAME")")"
  cluster_name="${cluster_name:-videochat}"
  cluster_location="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_CLUSTER_LOCATION")")"
  cluster_location="${CLUSTER_LOCATION_OVERRIDE:-${cluster_location:-${region}-a}}"
  machine_type="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_MACHINE_TYPE")")"
  machine_type="${MACHINE_TYPE_OVERRIDE:-${machine_type:-e2-standard-2}}"
  node_count="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_NODE_COUNT")")"
  node_count="${NODE_COUNT_OVERRIDE:-${node_count:-2}}"
  release_channel="$(trim_cr "$(read_env_value "${ENV_FILE}" "GKE_RELEASE_CHANNEL")")"
  release_channel="${RELEASE_CHANNEL_OVERRIDE:-${release_channel:-regular}}"
  gateway_name="$(trim_cr "$(read_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_NAME")")"
  gateway_name="${gateway_name:-${cluster_name}-external}"
  gateway_address_name="$(trim_cr "$(read_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_ADDRESS_NAME")")"
  gateway_address_name="${gateway_address_name:-${cluster_name}-ip}"
  gateway_certificate_name="$(trim_cr "$(read_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME")")"
  gateway_certificate_name="${gateway_certificate_name:-${cluster_name}-cert}"
  image_registry="${region}-docker.pkg.dev/${project_id}/${artifact_repo}"
  image_tag="$(derive_image_tag)"

  write_env_value "${ENV_FILE}" "GCP_PROJECT_ID" "${project_id}"
  ensure_env_value "${ENV_FILE}" "GCP_PROJECT_NAME" "PulseRoom Videochat"
  ensure_env_value "${ENV_FILE}" "GCP_BILLING_ACCOUNT" ""
  ensure_env_value "${ENV_FILE}" "GCP_PROJECT_ORGANIZATION_ID" ""
  ensure_env_value "${ENV_FILE}" "GCP_PROJECT_FOLDER_ID" ""
  ensure_env_value "${ENV_FILE}" "GCP_REGION" "${region}"
  ensure_env_value "${ENV_FILE}" "K8S_NAMESPACE" "videochat"
  write_env_value "${ENV_FILE}" "K8S_EXPOSURE_MODE" "gke-gateway"
  ensure_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_CLASS" "gke-l7-global-external-managed"
  write_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_NAME" "${gateway_name}"
  write_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_ADDRESS_NAME" "${gateway_address_name}"
  write_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME" "${gateway_certificate_name}"
  ensure_env_value "${ENV_FILE}" "K8S_GKE_GATEWAY_BACKEND_TIMEOUT_SEC" "3600"
  ensure_env_value "${ENV_FILE}" "GKE_CLUSTER_NAME" "${cluster_name}"
  write_env_value "${ENV_FILE}" "GKE_CLUSTER_LOCATION" "${cluster_location}"
  write_env_value "${ENV_FILE}" "GKE_MACHINE_TYPE" "${machine_type}"
  write_env_value "${ENV_FILE}" "GKE_NODE_COUNT" "${node_count}"
  write_env_value "${ENV_FILE}" "GKE_RELEASE_CHANNEL" "${release_channel}"
  ensure_env_value "${ENV_FILE}" "GKE_ARTIFACT_REPOSITORY" "${artifact_repo}"
  write_env_value "${ENV_FILE}" "K8S_IMAGE_REGISTRY" "${image_registry}"
  if [[ "${SKIP_BUILD}" == "true" ]]; then
    ensure_env_value "${ENV_FILE}" "K8S_IMAGE_TAG" "${image_tag}"
  else
    write_env_value "${ENV_FILE}" "K8S_IMAGE_TAG" "${image_tag}"
  fi
  write_env_value "${ENV_FILE}" "K8S_PUSH_IMAGES" "true"

  ensure_env_value "${ENV_FILE}" "SOCIAL_DB_PASSWORD" "$(random_alnum 32)"
  ensure_env_value "${ENV_FILE}" "MINIO_ROOT_PASSWORD" "$(random_alnum 32)"
  ensure_env_value "${ENV_FILE}" "GRAFANA_ADMIN_PASSWORD" "$(random_alnum 24)"

  local google_client_id
  google_client_id="$(trim_cr "$(read_env_value "${ENV_FILE}" "GOOGLE_OAUTH_CLIENT_ID")")"
  if [[ -n "${google_client_id}" ]]; then
    ensure_env_value "${ENV_FILE}" "BROKER_JWT_GOOGLE_AUDIENCE" "${google_client_id}"
    ensure_env_value "${ENV_FILE}" "BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE" "${google_client_id}"
  fi
}

# Project setup is intentionally idempotent so reruns can recover from partially
# completed bootstrap attempts.
project_exists() {
  gcloud projects describe "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

project_has_billing() {
  local enabled

  enabled="$(
    gcloud billing projects describe "${GCP_PROJECT_ID}" \
      --format='value(billingEnabled)' 2>/dev/null || true
  )"
  [[ "${enabled}" == "True" || "${enabled}" == "true" ]]
}

link_project_billing() {
  [[ -n "${GCP_BILLING_ACCOUNT:-}" ]] || return 0

  if project_has_billing; then
    return
  fi

  gcloud billing projects link "${GCP_PROJECT_ID}" \
    --billing-account="${GCP_BILLING_ACCOUNT}" >/dev/null
}

ensure_project() {
  local create_args

  if project_exists; then
    log_info "Google Cloud project ${GCP_PROJECT_ID} already exists."
    link_project_billing
    return
  fi

  log_info "Creating Google Cloud project ${GCP_PROJECT_ID}."
  create_args=("${GCP_PROJECT_ID}" "--name=$(project_display_name)" "--set-as-default")
  if [[ -n "${GCP_PROJECT_FOLDER_ID:-}" ]]; then
    create_args+=("--folder=${GCP_PROJECT_FOLDER_ID}")
  elif [[ -n "${GCP_PROJECT_ORGANIZATION_ID:-}" ]]; then
    create_args+=("--organization=${GCP_PROJECT_ORGANIZATION_ID}")
  fi

  run_with_log_mode gcloud projects create "${create_args[@]}"
  run_with_log_mode gcloud config set project "${GCP_PROJECT_ID}"

  if [[ -n "${GCP_BILLING_ACCOUNT:-}" ]]; then
    link_project_billing
  else
    echo "Project ${GCP_PROJECT_ID} was created without a billing account link." >&2
    echo "Set GCP_BILLING_ACCOUNT in ${ENV_FILE} or run 'gcloud billing projects link ${GCP_PROJECT_ID} --billing-account=ACCOUNT_ID' before creating billable resources." >&2
  fi
}

enable_apis() {
  local services=(
    container.googleapis.com
    compute.googleapis.com
    artifactregistry.googleapis.com
  )

  if [[ -n "${GCP_DNS_MANAGED_ZONE:-}" && "${SKIP_DNS_UPDATE}" != "true" ]]; then
    services+=(dns.googleapis.com)
  fi

  log_info "Enabling APIs: ${services[*]}"
  run_with_log_mode gcloud services enable "${services[@]}" --project "${GCP_PROJECT_ID}"
}

artifact_repository_exists() {
  gcloud artifacts repositories describe \
    "${GKE_ARTIFACT_REPOSITORY}" \
    --location "${GCP_REGION}" \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

# Bootstrap owns only the repository needed for this stack's three app images.
ensure_artifact_repository() {
  if artifact_repository_exists; then
    log_info "Artifact Registry repository ${GKE_ARTIFACT_REPOSITORY} already exists in ${GCP_REGION}."
    return
  fi

  log_info "Creating Artifact Registry repository ${GKE_ARTIFACT_REPOSITORY} in ${GCP_REGION}."
  run_with_log_mode gcloud artifacts repositories create "${GKE_ARTIFACT_REPOSITORY}" \
    --project "${GCP_PROJECT_ID}" \
    --location "${GCP_REGION}" \
    --repository-format docker \
    --description "Video chat stack images"
}

configure_docker_auth() {
  log_info "Configuring Docker auth for ${GCP_REGION}-docker.pkg.dev."
  run_with_log_mode gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet
}

cluster_exists() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

cluster_primary_node_pool_name() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" \
    --format='value(nodePools[0].name)' 2>/dev/null | tr -d '\r'
}

current_cluster_node_count() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" \
    --format='value(currentNodeCount)' 2>/dev/null | tr -d '\r'
}

current_cluster_machine_type() {
  gcloud container clusters describe \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}" \
    --format='value(nodePools[0].config.machineType)' 2>/dev/null | tr -d '\r'
}

# Reuse the cluster when present; otherwise create the minimal Standard GKE
# shape required by the Gateway-based deployment.
ensure_cluster() {
  local current_node_count current_machine_type node_pool_name

  if [[ "${SKIP_CLUSTER_CREATE}" == "true" ]]; then
    log_skip "cluster create/update because --skip-cluster-create was provided."
    return
  fi

  if cluster_exists; then
    log_info "Cluster ${GKE_CLUSTER_NAME} already exists. Ensuring Gateway API is enabled."
    run_with_log_mode gcloud container clusters update "${GKE_CLUSTER_NAME}" \
      --location "${GKE_CLUSTER_LOCATION}" \
      --project "${GCP_PROJECT_ID}" \
      --gateway-api=standard

    current_node_count="$(current_cluster_node_count)"
    node_pool_name="$(cluster_primary_node_pool_name)"
    if [[ -n "${current_node_count}" && -n "${node_pool_name}" && "${current_node_count}" != "${GKE_NODE_COUNT}" ]]; then
      log_info "Resizing node pool ${node_pool_name} from ${current_node_count} to ${GKE_NODE_COUNT} nodes."
      run_with_log_mode gcloud container clusters resize "${GKE_CLUSTER_NAME}" \
        --location "${GKE_CLUSTER_LOCATION}" \
        --project "${GCP_PROJECT_ID}" \
        --node-pool "${node_pool_name}" \
        --num-nodes "${GKE_NODE_COUNT}" \
        --quiet
    fi

    current_machine_type="$(current_cluster_machine_type)"
    if [[ -n "${current_machine_type}" && "${current_machine_type}" != "${GKE_MACHINE_TYPE}" ]]; then
      echo "Requested machine type ${GKE_MACHINE_TYPE} differs from existing ${current_machine_type}." >&2
      echo "Bootstrap does not mutate machine types on an existing node pool; recreate the cluster or replace the node pool to apply that change." >&2
    fi
    return
  fi

  log_info "Creating cluster ${GKE_CLUSTER_NAME} in ${GKE_CLUSTER_LOCATION}."
  run_with_log_mode gcloud container clusters create "${GKE_CLUSTER_NAME}" \
    --project "${GCP_PROJECT_ID}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --release-channel "${GKE_RELEASE_CHANNEL}" \
    --machine-type "${GKE_MACHINE_TYPE}" \
    --num-nodes "${GKE_NODE_COUNT}" \
    --enable-ip-alias \
    --gateway-api=standard
}

configure_kubectl_context() {
  log_info "Fetching kubectl credentials for ${GKE_CLUSTER_NAME}."
  run_with_log_mode gcloud container clusters get-credentials \
    "${GKE_CLUSTER_NAME}" \
    --location "${GKE_CLUSTER_LOCATION}" \
    --project "${GCP_PROJECT_ID}"
}

# GatewayClass creation is asynchronous after enabling Gateway API on GKE.
wait_for_gateway_class() {
  local attempts=60

  log_info "Waiting for GatewayClass ${K8S_GKE_GATEWAY_CLASS} to appear."
  while (( attempts > 0 )); do
    if kubectl get gatewayclass "${K8S_GKE_GATEWAY_CLASS}" >/dev/null 2>&1; then
      log_info "GatewayClass ${K8S_GKE_GATEWAY_CLASS} is available."
      return
    fi
    if (( attempts % 6 == 0 )); then
      log_info "GatewayClass not ready yet; retrying..."
    fi
    sleep 10
    attempts=$((attempts - 1))
  done

  fail "GatewayClass ${K8S_GKE_GATEWAY_CLASS} did not appear in the cluster."
}

# Map all PVCs to the default storage class unless the operator already pinned
# explicit class names in k8s.env.
seed_storage_classes() {
  local storage_class key

  storage_class="$(default_storage_class_name)"
  if [[ -z "${storage_class}" ]]; then
    log_skip "storage class seeding because no default storage class was found."
    return 0
  fi

  log_info "Using default storage class ${storage_class} for unset PVC classes."

  for key in \
    K8S_SOCIAL_DB_STORAGE_CLASS \
    K8S_MINIO_STORAGE_CLASS \
    K8S_PROMETHEUS_STORAGE_CLASS \
    K8S_LOKI_STORAGE_CLASS \
    K8S_GRAFANA_STORAGE_CLASS; do
    if ! storage_class_exists "$(trim_cr "$(read_env_value "${ENV_FILE}" "${key}")")"; then
      write_env_value "${ENV_FILE}" "${key}" "${storage_class}"
    fi
  done
}

ensure_required_inputs() {
  [[ "${K8S_HOST}" != "videochat.local" ]] || fail "Set K8S_HOST in ${ENV_FILE} to the public hostname you want to expose."
  [[ -n "${GOOGLE_OAUTH_CLIENT_ID:-}" ]] || fail "Set GOOGLE_OAUTH_CLIENT_ID in ${ENV_FILE} before running the GKE bootstrap."
}

address_exists() {
  gcloud compute addresses describe \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

# Reserve a stable global IPv4 so DNS and the managed certificate can survive
# redeployments of the Kubernetes resources.
ensure_static_ip() {
  if address_exists; then
    log_info "Global static IP ${K8S_GKE_GATEWAY_ADDRESS_NAME} already exists."
    return
  fi

  log_info "Creating global static IP ${K8S_GKE_GATEWAY_ADDRESS_NAME}."
  run_with_log_mode gcloud compute addresses create "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}"
}

fetch_static_ip() {
  gcloud compute addresses describe \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format='value(address)'
}

certificate_exists() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

ensure_ssl_certificate() {
  if certificate_exists; then
    log_info "Managed SSL certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME} already exists."
    return
  fi

  log_info "Creating managed SSL certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME} for ${K8S_HOST}."
  run_with_log_mode gcloud compute ssl-certificates create "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --domains "${K8S_HOST}" \
    --global \
    --project "${GCP_PROJECT_ID}"
}

# Cloud DNS updates are optional because many users host their public DNS
# elsewhere and only need the final static IP printed.
ensure_dns_record() {
  local fqdn current_records

  if [[ -z "${GCP_DNS_MANAGED_ZONE:-}" ]]; then
    log_skip "DNS update because GCP_DNS_MANAGED_ZONE is not set."
    return 0
  fi
  if [[ "${SKIP_DNS_UPDATE}" == "true" ]]; then
    log_skip "DNS update because --skip-dns-update was provided."
    return 0
  fi

  fqdn="${K8S_HOST%.}."
  mapfile -t current_records < <(
    gcloud dns record-sets list \
      --project "${GCP_PROJECT_ID}" \
      --zone "${GCP_DNS_MANAGED_ZONE}" \
      --name "${fqdn}" \
      --type A \
      --format='value(rrdatas)'
  )

  if (( ${#current_records[@]} == 1 )) && [[ "${current_records[0]}" == "${STATIC_IP}" ]]; then
    log_info "DNS A record ${fqdn} already points to ${STATIC_IP}."
    return
  fi

  log_info "Updating Cloud DNS record ${fqdn} -> ${STATIC_IP} in zone ${GCP_DNS_MANAGED_ZONE}."
  gcloud dns record-sets transaction abort \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null 2>&1 || true
  gcloud dns record-sets transaction start \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null

  if (( ${#current_records[@]} > 0 )); then
    for record in "${current_records[@]}"; do
      gcloud dns record-sets transaction remove "${record}" \
        --project "${GCP_PROJECT_ID}" \
        --zone "${GCP_DNS_MANAGED_ZONE}" \
        --name "${fqdn}" \
        --type A \
        --ttl 300 >/dev/null
    done
  fi

  gcloud dns record-sets transaction add "${STATIC_IP}" \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" \
    --name "${fqdn}" \
    --type A \
    --ttl 300 >/dev/null

  run_with_log_mode gcloud dns record-sets transaction execute \
    --project "${GCP_PROJECT_ID}" \
    --zone "${GCP_DNS_MANAGED_ZONE}" >/dev/null
}

# Wait until the Gateway advertises the reserved global IP before reporting the
# deployment as complete.
wait_for_gateway_address() {
  local attempts=90 current_ip

  log_info "Waiting for Gateway ${K8S_GKE_GATEWAY_NAME} to advertise ${STATIC_IP}."
  while (( attempts > 0 )); do
    current_ip="$(kubectl get gateway "${K8S_GKE_GATEWAY_NAME}" --namespace "${K8S_NAMESPACE}" -o jsonpath='{.status.addresses[0].value}' 2>/dev/null || true)"
    if [[ -n "${current_ip}" && "${current_ip}" == "${STATIC_IP}" ]]; then
      log_info "Gateway is now advertising ${STATIC_IP}."
      return
    fi
    if (( attempts % 9 == 0 )); then
      log_info "Gateway address not ready yet; current value: ${current_ip:-<none>}"
    fi
    sleep 10
    attempts=$((attempts - 1))
  done

  echo "Gateway ${K8S_GKE_GATEWAY_NAME} did not report ${STATIC_IP} yet." >&2
}

certificate_status() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format='value(managed.status)'
}

delete_namespace_if_requested() {
  if [[ "${RECREATE_NAMESPACE}" != "true" ]]; then
    log_skip "namespace recreation because --recreate-namespace was not provided."
    return
  fi

  log_info "Deleting namespace ${K8S_NAMESPACE} before redeploying."
  run_with_log_mode kubectl delete namespace "${K8S_NAMESPACE}" --ignore-not-found --wait=true
}

# Bootstrap execution order:
# - prepare env and cloud resources first
# - then build/validate/apply the Kubernetes bundle
# - finally wait for the public Gateway wiring to settle
log_step "Preparing environment defaults"
seed_env_defaults
load_k8s_env "${ENV_FILE}"
ensure_required_inputs

log_info "Env file: ${ENV_FILE}"
log_info "Project: ${GCP_PROJECT_ID}"
log_info "Region: ${GCP_REGION}"
log_info "Cluster: ${GKE_CLUSTER_NAME} (${GKE_CLUSTER_LOCATION})"
log_info "Machine type: ${GKE_MACHINE_TYPE}"
log_info "Node count: ${GKE_NODE_COUNT}"
log_info "Release channel: ${GKE_RELEASE_CHANNEL}"
log_info "Namespace: ${K8S_NAMESPACE}"
log_info "Host: ${K8S_HOST}"
log_info "Image registry: ${K8S_IMAGE_REGISTRY}"
log_info "Image tag: ${K8S_IMAGE_TAG}"

log_step "Estimated cost profile"
echo "$(cost_summary)"

log_step "Ensuring Google Cloud project"
ensure_project

log_step "Enabling required Google Cloud APIs"
enable_apis

log_step "Ensuring Artifact Registry repository"
ensure_artifact_repository

log_step "Configuring Docker authentication"
configure_docker_auth

log_step "Ensuring GKE cluster"
ensure_cluster

log_step "Configuring kubectl context"
configure_kubectl_context

log_step "Waiting for Gateway API readiness"
wait_for_gateway_class

log_step "Seeding storage classes"
seed_storage_classes
load_k8s_env "${ENV_FILE}"

log_step "Ensuring public edge resources"
ensure_static_ip
STATIC_IP="$(fetch_static_ip)"
log_info "Static IP address: ${STATIC_IP}"
ensure_ssl_certificate
ensure_dns_record

log_step "Preparing Kubernetes namespace"
delete_namespace_if_requested

if [[ "${SKIP_BUILD}" != "true" ]]; then
  log_step "Building and pushing application images"
  run_with_log_mode "${K8S_SCRIPT_DIR}/build-images.sh" "${ENV_FILE}"
else
  log_step "Skipping image build"
  log_info "Reusing configured image tag ${K8S_IMAGE_TAG}."
fi

log_step "Validating rendered manifests"
run_with_log_mode "${K8S_SCRIPT_DIR}/validate.sh" "${ENV_FILE}"

log_step "Applying Kubernetes manifests"
run_with_log_mode "${K8S_SCRIPT_DIR}/apply.sh" "${ENV_FILE}"

log_step "Waiting for Gateway address propagation"
wait_for_gateway_address

cat <<EOF
GKE deployment complete.

Namespace: ${K8S_NAMESPACE}
Host: https://${K8S_HOST}/
Static IP: ${STATIC_IP}
Gateway: ${K8S_GKE_GATEWAY_NAME}
Image registry: ${K8S_IMAGE_REGISTRY}
Image tag: ${K8S_IMAGE_TAG}
SSL certificate status: $(certificate_status)
Cost rating: $(cost_rating)
Billing linked: $(project_has_billing && printf 'true' || printf 'false')

If the certificate status is not ACTIVE yet, wait for DNS propagation and Google-managed
certificate provisioning to finish before sending end users to the URL above.

Create or update this public DNS record:
A ${K8S_HOST} -> ${STATIC_IP}
EOF

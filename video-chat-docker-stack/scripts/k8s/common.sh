#!/usr/bin/env bash
set -euo pipefail

# Shared helpers for the Kubernetes and GKE shell entrypoints.
# Keep this file free of side effects so other scripts can source it safely.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${ROOT_DIR}/k8s"
RENDER_DIR="${K8S_DIR}/rendered"
ENV_FILE_DEFAULT="${K8S_DIR}/k8s.env"
STEP_COUNTER=0
LOG_LEVEL="${LOG_LEVEL:-verbose}"

# Emit a single actionable error and stop the current entrypoint.
fail() {
  echo "$*" >&2
  exit 1
}

log_step() {
  [[ "${LOG_LEVEL}" == "quiet" ]] && return 0
  STEP_COUNTER=$((STEP_COUNTER + 1))
  printf '\n[%02d] %s\n' "${STEP_COUNTER}" "$*"
}

log_info() {
  [[ "${LOG_LEVEL}" == "quiet" ]] && return 0
  printf '     %s\n' "$*"
}

log_skip() {
  [[ "${LOG_LEVEL}" == "quiet" ]] && return 0
  printf '     Skipping: %s\n' "$*"
}

set_log_level() {
  case "$1" in
    verbose|quiet)
      LOG_LEVEL="$1"
      export LOG_LEVEL
      ;;
    *)
      fail "Unsupported log level: $1"
      ;;
  esac
}

run_with_log_mode() {
  if [[ "${LOG_LEVEL}" == "quiet" ]]; then
    "$@" >/dev/null
    return
  fi

  "$@"
}

# Validate external tooling as early as possible so later failures are not cryptic.
require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

# Normalize env values that may have been edited on Windows.
trim_cr() {
  printf '%s' "${1%$'\r'}"
}

# Read a simple KEY=value pair while ignoring comments.
read_env_value() {
  local file="$1"
  local key="$2"

  [[ -f "${file}" ]] || return 0

  awk -F= -v key="${key}" '
    $0 ~ "^[[:space:]]*#" { next }
    $1 == key {
      sub(/^[^=]*=/, "", $0)
      print $0
      exit
    }
  ' "${file}"
}

# Update or append one KEY=value pair without disturbing unrelated env entries.
write_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp_file

  tmp_file="$(mktemp)"

  if [[ -f "${file}" ]]; then
    awk -F= -v key="${key}" -v value="${value}" '
      BEGIN { updated = 0 }
      $1 == key {
        print key "=" value
        updated = 1
        next
      }
      { print }
      END {
        if (!updated) {
          print key "=" value
        }
      }
    ' "${file}" > "${tmp_file}"
  else
    printf '%s=%s\n' "${key}" "${value}" > "${tmp_file}"
  fi

  mv "${tmp_file}" "${file}"
}

# Seed a default only when the target key is currently blank.
ensure_env_value() {
  local file="$1"
  local key="$2"
  local desired="$3"
  local current

  current="$(trim_cr "$(read_env_value "${file}" "${key}")")"
  if [[ -z "${current}" ]]; then
    write_env_value "${file}" "${key}" "${desired}"
  fi
}

# Seed a default when the key is blank or still contains an older placeholder value.
ensure_env_value_if_equals() {
  local file="$1"
  local key="$2"
  local old_value="$3"
  local desired="$4"
  local current

  current="$(trim_cr "$(read_env_value "${file}" "${key}")")"
  if [[ -z "${current}" || "${current}" == "${old_value}" ]]; then
    write_env_value "${file}" "${key}" "${desired}"
  fi
}

# Load the deployment env file, export all variables, and derive the image names
# used by the render/apply/build entrypoints.
load_k8s_env() {
  local env_file="${1:-${ENV_FILE_DEFAULT}}"
  local line

  [[ -f "${env_file}" ]] || fail "Missing Kubernetes env file: ${env_file}. Copy ${K8S_DIR}/k8s.env.example first."

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -n "${line}" ]] || continue
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    export "${line}"
  done < "${env_file}"

  : "${K8S_EXPOSURE_MODE:=ingress-nginx}"
  : "${K8S_NAMESPACE:?K8S_NAMESPACE is required}"
  : "${K8S_HOST:?K8S_HOST is required}"
  : "${K8S_IMAGE_TAG:?K8S_IMAGE_TAG is required}"
  : "${K8S_SOCIAL_DB_STORAGE_CLASS:?K8S_SOCIAL_DB_STORAGE_CLASS is required}"
  : "${K8S_SOCIAL_DB_STORAGE_SIZE:?K8S_SOCIAL_DB_STORAGE_SIZE is required}"
  : "${K8S_MINIO_STORAGE_CLASS:?K8S_MINIO_STORAGE_CLASS is required}"
  : "${K8S_MINIO_STORAGE_SIZE:?K8S_MINIO_STORAGE_SIZE is required}"
  : "${K8S_PROMETHEUS_STORAGE_CLASS:?K8S_PROMETHEUS_STORAGE_CLASS is required}"
  : "${K8S_PROMETHEUS_STORAGE_SIZE:?K8S_PROMETHEUS_STORAGE_SIZE is required}"
  : "${K8S_LOKI_STORAGE_CLASS:?K8S_LOKI_STORAGE_CLASS is required}"
  : "${K8S_LOKI_STORAGE_SIZE:?K8S_LOKI_STORAGE_SIZE is required}"
  : "${K8S_GRAFANA_STORAGE_CLASS:?K8S_GRAFANA_STORAGE_CLASS is required}"
  : "${K8S_GRAFANA_STORAGE_SIZE:?K8S_GRAFANA_STORAGE_SIZE is required}"
  : "${SOCIAL_DB_NAME:?SOCIAL_DB_NAME is required}"
  : "${SOCIAL_DB_USER:?SOCIAL_DB_USER is required}"
  : "${SOCIAL_DB_PASSWORD:?SOCIAL_DB_PASSWORD is required}"
  : "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
  : "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
  : "${GRAFANA_ADMIN_USER:?GRAFANA_ADMIN_USER is required}"
  : "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required}"

  case "${K8S_EXPOSURE_MODE}" in
    ingress-nginx)
      : "${K8S_INGRESS_CLASS_NAME:?K8S_INGRESS_CLASS_NAME is required when K8S_EXPOSURE_MODE=ingress-nginx}"
      : "${K8S_TLS_SECRET_NAME:?K8S_TLS_SECRET_NAME is required when K8S_EXPOSURE_MODE=ingress-nginx}"
      ;;
    gke-gateway)
      : "${K8S_GKE_GATEWAY_NAME:?K8S_GKE_GATEWAY_NAME is required when K8S_EXPOSURE_MODE=gke-gateway}"
      : "${K8S_GKE_GATEWAY_CLASS:?K8S_GKE_GATEWAY_CLASS is required when K8S_EXPOSURE_MODE=gke-gateway}"
      : "${K8S_GKE_GATEWAY_ADDRESS_NAME:?K8S_GKE_GATEWAY_ADDRESS_NAME is required when K8S_EXPOSURE_MODE=gke-gateway}"
      : "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME:?K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME is required when K8S_EXPOSURE_MODE=gke-gateway}"
      : "${K8S_GKE_GATEWAY_BACKEND_TIMEOUT_SEC:=3600}"
      ;;
    *)
      fail "Unsupported K8S_EXPOSURE_MODE: ${K8S_EXPOSURE_MODE}"
      ;;
  esac

  if [[ -n "${K8S_IMAGE_REGISTRY:-}" ]]; then
    FRONTEND_IMAGE="${K8S_IMAGE_REGISTRY%/}/video-chat-angular:${K8S_IMAGE_TAG}"
    BROKER_IMAGE="${K8S_IMAGE_REGISTRY%/}/video-chat-rsocket-broker:${K8S_IMAGE_TAG}"
    BACKOFFICE_IMAGE="${K8S_IMAGE_REGISTRY%/}/video-chat-backoffice:${K8S_IMAGE_TAG}"
  else
    FRONTEND_IMAGE="video-chat-angular:${K8S_IMAGE_TAG}"
    BROKER_IMAGE="video-chat-rsocket-broker:${K8S_IMAGE_TAG}"
    BACKOFFICE_IMAGE="video-chat-backoffice:${K8S_IMAGE_TAG}"
  fi

  export ROOT_DIR K8S_DIR RENDER_DIR
  export FRONTEND_IMAGE BROKER_IMAGE BACKOFFICE_IMAGE
}

# Start each render with a clean output folder so apply/delete operate on one
# coherent manifest set.
prepare_render_dir() {
  rm -rf "${RENDER_DIR}"
  mkdir -p "${RENDER_DIR}"
}

# Replace ${UPPERCASE_VAR} placeholders directly from the current shell env.
render_template() {
  local template_path="$1"
  local output_path="$2"

  perl -pe 's/\$\{([A-Z0-9_]+)\}/exists $ENV{$1} ? $ENV{$1} : $&/ge' \
    "${template_path}" > "${output_path}"
}

# Use kubectl's client-side generator so generated ConfigMaps match the target
# cluster's API defaults without hand-maintaining YAML.
generate_configmap_yaml() {
  local output_path="$1"
  local name="$2"
  shift 2

  kubectl create configmap "${name}" \
    --namespace "${K8S_NAMESPACE}" \
    "$@" \
    --dry-run=client \
    -o yaml > "${output_path}"
}

# Resolve the singleton social-db pod for backup/restore workflows.
resolve_social_db_pod() {
  kubectl get pod \
    --namespace "${K8S_NAMESPACE}" \
    -l app.kubernetes.io/name=social-db \
    -o jsonpath='{.items[0].metadata.name}'
}

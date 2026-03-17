#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${ROOT_DIR}/k8s"
RENDER_DIR="${K8S_DIR}/rendered"
ENV_FILE_DEFAULT="${K8S_DIR}/k8s.env"

fail() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

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

  : "${K8S_NAMESPACE:?K8S_NAMESPACE is required}"
  : "${K8S_HOST:?K8S_HOST is required}"
  : "${K8S_INGRESS_CLASS_NAME:?K8S_INGRESS_CLASS_NAME is required}"
  : "${K8S_TLS_SECRET_NAME:?K8S_TLS_SECRET_NAME is required}"
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

prepare_render_dir() {
  rm -rf "${RENDER_DIR}"
  mkdir -p "${RENDER_DIR}"
}

render_template() {
  local template_path="$1"
  local output_path="$2"

  perl -pe 's/\$\{([A-Z0-9_]+)\}/exists $ENV{$1} ? $ENV{$1} : $&/ge' \
    "${template_path}" > "${output_path}"
}

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

resolve_social_db_pod() {
  kubectl get pod \
    --namespace "${K8S_NAMESPACE}" \
    -l app.kubernetes.io/name=social-db \
    -o jsonpath='{.items[0].metadata.name}'
}

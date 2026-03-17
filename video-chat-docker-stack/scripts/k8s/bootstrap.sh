#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl
require_command docker
require_command openssl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/bootstrap.sh [options]

Rebuild the Kubernetes stack from the local repo state. The script can:
- create or update k8s/k8s.env from the Docker Compose .env
- install ingress-nginx when the nginx ingress class is missing
- optionally delete the existing namespace for a clean rebuild
- build images, validate manifests, create a self-signed TLS secret, and apply the stack
- expose Docker Desktop ingress on :8080/:8443 when needed

Options:
  --env <path>                  Use a specific env file instead of k8s/k8s.env
  --recreate-namespace          Delete the target namespace before deploying
  --skip-build                  Reuse existing image tags instead of rebuilding
  --skip-self-signed-tls        Do not recreate the TLS secret
  --skip-ingress-install        Do not install ingress-nginx automatically
  --skip-docker-desktop-expose  Do not patch Docker Desktop ingress to :8080/:8443
  --help                        Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
RECREATE_NAMESPACE=false
SKIP_BUILD=false
SKIP_SELF_SIGNED_TLS=false
SKIP_INGRESS_INSTALL=false
SKIP_DOCKER_DESKTOP_EXPOSE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --recreate-namespace)
      RECREATE_NAMESPACE=true
      shift
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-self-signed-tls)
      SKIP_SELF_SIGNED_TLS=true
      shift
      ;;
    --skip-ingress-install)
      SKIP_INGRESS_INSTALL=true
      shift
      ;;
    --skip-docker-desktop-expose)
      SKIP_DOCKER_DESKTOP_EXPOSE=true
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

COMPOSE_ENV_FILE="${ROOT_DIR}/.env"
DEFAULT_INGRESS_NGINX_MANIFEST_URL="${K8S_INGRESS_NGINX_MANIFEST_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.2/deploy/static/provider/cloud/deploy.yaml}"
INGRESS_INSTALLED_BY_BOOTSTRAP=false

trim_cr() {
  printf '%s' "${1%$'\r'}"
}

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

random_alnum() {
  local length="${1:-32}"

  openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | head -c "${length}"
}

current_context() {
  kubectl config current-context 2>/dev/null || true
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

seed_env_from_local_stack() {
  local context host storage_class google_client_id
  local social_db_password minio_root_password grafana_admin_password

  mkdir -p "$(dirname "${ENV_FILE}")"

  if [[ ! -f "${ENV_FILE}" ]]; then
    cp "${K8S_DIR}/k8s.env.example" "${ENV_FILE}"
    echo "Created ${ENV_FILE} from k8s.env.example"
  fi

  context="$(current_context)"
  host="videochat.local"
  if [[ "${context}" == "docker-desktop" ]]; then
    host="videochat.127.0.0.1.nip.io"
  fi

  ensure_env_value_if_equals "${ENV_FILE}" "K8S_HOST" "videochat.local" "${host}"
  ensure_env_value "${ENV_FILE}" "K8S_NAMESPACE" "videochat"
  ensure_env_value "${ENV_FILE}" "K8S_INGRESS_CLASS_NAME" "nginx"
  ensure_env_value "${ENV_FILE}" "K8S_TLS_SECRET_NAME" "videochat-tls"

  if [[ "${context}" == "docker-desktop" ]]; then
    ensure_env_value_if_equals "${ENV_FILE}" "K8S_IMAGE_TAG" "latest" "local"
  fi

  storage_class="$(default_storage_class)"
  if [[ -n "${storage_class}" ]]; then
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
  fi

  google_client_id="$(trim_cr "$(read_env_value "${COMPOSE_ENV_FILE}" "GOOGLE_OAUTH_CLIENT_ID")")"
  if [[ -n "${google_client_id}" ]]; then
    ensure_env_value "${ENV_FILE}" "GOOGLE_OAUTH_CLIENT_ID" "${google_client_id}"
    ensure_env_value "${ENV_FILE}" "BROKER_JWT_GOOGLE_AUDIENCE" "${google_client_id}"
    ensure_env_value "${ENV_FILE}" "BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE" "${google_client_id}"
  fi

  for key in \
    VIDEOCHAT_APP_MODE \
    BROKER_JWT_ENABLED \
    BROKER_JWT_CACHE_TTL \
    BROKER_JWT_CLOCK_SKEW \
    BROKER_JWT_GOOGLE_ENABLED \
    BROKER_JWT_GOOGLE_AUDIENCE \
    BROKER_JWT_APPLE_ENABLED \
    BROKER_JWT_X_ENABLED \
    SOCIAL_DB_NAME \
    SOCIAL_DB_USER \
    MINIO_ROOT_USER \
    GRAFANA_ADMIN_USER \
    BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE \
    BACKOFFICE_SOCIAL_MEDIA_ENABLED \
    BACKOFFICE_SOCIAL_MEDIA_BUCKET \
    BACKOFFICE_SOCIAL_MEDIA_MAX_UPLOAD_BYTES \
    BACKOFFICE_SOCIAL_MEDIA_MAX_FILES_PER_POST; do
    local value
    value="$(trim_cr "$(read_env_value "${COMPOSE_ENV_FILE}" "${key}")")"
    if [[ -n "${value}" ]]; then
      ensure_env_value "${ENV_FILE}" "${key}" "${value}"
    fi
  done

  for mapping in \
    "BROKER_JWT_GOOGLE_JWK_SET_URI:VAULT_GOOGLE_JWKS_URL" \
    "BROKER_JWT_APPLE_JWK_SET_URI:VAULT_APPLE_JWKS_URL" \
    "BROKER_JWT_X_JWK_SET_URI:VAULT_X_JWKS_URL" \
    "SOCIAL_DB_PASSWORD:VAULT_BOOTSTRAP_SOCIAL_DB_PASSWORD" \
    "MINIO_ROOT_PASSWORD:VAULT_BOOTSTRAP_MINIO_ROOT_PASSWORD" \
    "GRAFANA_ADMIN_PASSWORD:VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD"; do
    local target_key source_key value
    target_key="${mapping%%:*}"
    source_key="${mapping##*:}"
    value="$(trim_cr "$(read_env_value "${COMPOSE_ENV_FILE}" "${source_key}")")"
    if [[ -n "${value}" ]]; then
      ensure_env_value "${ENV_FILE}" "${target_key}" "${value}"
    fi
  done

  social_db_password="$(trim_cr "$(read_env_value "${ENV_FILE}" "SOCIAL_DB_PASSWORD")")"
  [[ -n "${social_db_password}" ]] || write_env_value "${ENV_FILE}" "SOCIAL_DB_PASSWORD" "$(random_alnum 32)"

  minio_root_password="$(trim_cr "$(read_env_value "${ENV_FILE}" "MINIO_ROOT_PASSWORD")")"
  [[ -n "${minio_root_password}" ]] || write_env_value "${ENV_FILE}" "MINIO_ROOT_PASSWORD" "$(random_alnum 32)"

  grafana_admin_password="$(trim_cr "$(read_env_value "${ENV_FILE}" "GRAFANA_ADMIN_PASSWORD")")"
  [[ -n "${grafana_admin_password}" ]] || write_env_value "${ENV_FILE}" "GRAFANA_ADMIN_PASSWORD" "$(random_alnum 32)"
}

ensure_ingress_controller() {
  if kubectl get ingressclass "${K8S_INGRESS_CLASS_NAME}" >/dev/null 2>&1; then
    return
  fi

  [[ "${K8S_INGRESS_CLASS_NAME}" == "nginx" ]] || fail "Ingress class ${K8S_INGRESS_CLASS_NAME} is missing. Install your ingress controller first or use ingress class nginx."

  echo "Installing ingress-nginx from ${DEFAULT_INGRESS_NGINX_MANIFEST_URL}"
  kubectl apply -f "${DEFAULT_INGRESS_NGINX_MANIFEST_URL}" >/dev/null
  kubectl rollout status deployment/ingress-nginx-controller --namespace ingress-nginx --timeout=300s
  kubectl annotate namespace ingress-nginx videochat.nextend/installed-by-bootstrap=true --overwrite >/dev/null
  INGRESS_INSTALLED_BY_BOOTSTRAP=true
}

delete_namespace_and_wait() {
  if ! kubectl get namespace "${K8S_NAMESPACE}" >/dev/null 2>&1; then
    return
  fi

  kubectl delete namespace "${K8S_NAMESPACE}" --wait=false >/dev/null

  until ! kubectl get namespace "${K8S_NAMESPACE}" >/dev/null 2>&1; do
    sleep 2
  done
}

seed_env_from_local_stack
load_k8s_env "${ENV_FILE}"

if [[ -z "${K8S_IMAGE_REGISTRY:-}" && "$(current_context)" != "docker-desktop" ]]; then
  echo "Warning: K8S_IMAGE_REGISTRY is blank and the current context is not docker-desktop." >&2
  echo "The cluster may not be able to pull images unless it can see the local Docker daemon." >&2
fi

if [[ "${SKIP_INGRESS_INSTALL}" != "true" ]]; then
  ensure_ingress_controller
fi

if [[ "${RECREATE_NAMESPACE}" == "true" ]]; then
  echo "Deleting namespace ${K8S_NAMESPACE} for a clean rebuild"
  delete_namespace_and_wait
fi

if [[ "${SKIP_BUILD}" != "true" ]]; then
  "${SCRIPT_DIR}/build-images.sh" "${ENV_FILE}"
fi

"${SCRIPT_DIR}/validate.sh" "${ENV_FILE}"

if [[ "${SKIP_SELF_SIGNED_TLS}" != "true" ]]; then
  "${SCRIPT_DIR}/create-self-signed-tls.sh" "${ENV_FILE}"
fi

"${SCRIPT_DIR}/apply.sh" "${ENV_FILE}"

if [[ "$(current_context)" == "docker-desktop" && "${SKIP_DOCKER_DESKTOP_EXPOSE}" != "true" ]]; then
  "${SCRIPT_DIR}/expose-docker-desktop.sh" "${ENV_FILE}"
fi

echo
echo "Bootstrap complete."
kubectl get pods,svc,ingress,pvc --namespace "${K8S_NAMESPACE}"

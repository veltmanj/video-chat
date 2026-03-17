#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/teardown.sh [options]

Tear down the Kubernetes stack and optionally reverse shared cluster setup that
bootstrap.sh created.

Options:
  --env <path>                  Use a specific env file instead of k8s/k8s.env
  --delete-ingress-nginx        Force uninstall ingress-nginx after deleting the app namespace
  --delete-env-file             Remove the local k8s env file after teardown
  --delete-rendered             Remove k8s/rendered after teardown
  --help                        Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
DELETE_INGRESS_NGINX=false
DELETE_ENV_FILE=false
DELETE_RENDERED=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --delete-ingress-nginx)
      DELETE_INGRESS_NGINX=true
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

DEFAULT_INGRESS_NGINX_MANIFEST_URL="${K8S_INGRESS_NGINX_MANIFEST_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.2/deploy/static/provider/cloud/deploy.yaml}"
INGRESS_NAMESPACE="${K8S_INGRESS_NGINX_NAMESPACE:-ingress-nginx}"
INGRESS_SERVICE="${K8S_INGRESS_NGINX_SERVICE:-ingress-nginx-controller}"
NAMESPACE_DELETE_TIMEOUT_SECONDS="${K8S_NAMESPACE_DELETE_TIMEOUT_SECONDS:-120}"

wait_for_namespace_deletion() {
  local namespace="$1"
  local remaining="${NAMESPACE_DELETE_TIMEOUT_SECONDS}"
  local finalize_payload

  while kubectl get namespace "${namespace}" >/dev/null 2>&1; do
    if (( remaining <= 0 )); then
      echo "Namespace ${namespace} is still terminating after ${NAMESPACE_DELETE_TIMEOUT_SECONDS}s. Clearing namespace finalizers." >&2
      finalize_payload="$(kubectl get namespace "${namespace}" -o json 2>/dev/null | perl -0pe 's/"finalizers":\s*\[[^\]]*\]/"finalizers":[]/')"
      if [[ -n "${finalize_payload}" ]]; then
        printf '%s' "${finalize_payload}" | kubectl replace --raw "/api/v1/namespaces/${namespace}/finalize" -f - >/dev/null 2>&1 || true
      fi
    fi

    sleep 2
    remaining=$(( remaining - 2 ))
  done
}

delete_namespace_and_wait() {
  if ! kubectl get namespace "${K8S_NAMESPACE}" >/dev/null 2>&1; then
    return
  fi

  kubectl delete namespace "${K8S_NAMESPACE}" --wait=false >/dev/null
  wait_for_namespace_deletion "${K8S_NAMESPACE}"
}

service_annotation() {
  local key="$1"

  kubectl get svc "${INGRESS_SERVICE}" \
    --namespace "${INGRESS_NAMESPACE}" \
    -o "jsonpath={.metadata.annotations.${key}}" 2>/dev/null || true
}

reset_docker_desktop_exposure() {
  local managed original_http original_https

  managed="$(service_annotation 'videochat\.nextend/docker-desktop-expose')"
  original_http="$(service_annotation 'videochat\.nextend/original-http-port')"
  original_https="$(service_annotation 'videochat\.nextend/original-https-port')"

  [[ -n "${managed}" ]] || return

  kubectl patch svc "${INGRESS_SERVICE}" \
    --namespace "${INGRESS_NAMESPACE}" \
    --type='merge' \
    -p "{
      \"metadata\": {
        \"annotations\": {
          \"videochat.nextend/docker-desktop-expose\": null,
          \"videochat.nextend/original-http-port\": null,
          \"videochat.nextend/original-https-port\": null
        }
      },
      \"spec\": {
        \"ports\": [
          {
            \"appProtocol\": \"http\",
            \"name\": \"http\",
            \"port\": ${original_http:-80},
            \"protocol\": \"TCP\",
            \"targetPort\": \"http\"
          },
          {
            \"appProtocol\": \"https\",
            \"name\": \"https\",
            \"port\": ${original_https:-443},
            \"protocol\": \"TCP\",
            \"targetPort\": \"https\"
          }
        ]
      }
    }" >/dev/null

  echo "Reset Docker Desktop ingress exposure on ${INGRESS_NAMESPACE}/${INGRESS_SERVICE}"
}

ingress_installed_by_bootstrap() {
  local managed

  managed="$(kubectl get namespace "${INGRESS_NAMESPACE}" -o jsonpath='{.metadata.annotations.videochat\.nextend/installed-by-bootstrap}' 2>/dev/null || true)"
  [[ "${managed}" == "true" ]]
}

delete_ingress_nginx_if_managed() {
  if [[ "${DELETE_INGRESS_NGINX}" != "true" ]] && ! ingress_installed_by_bootstrap; then
    echo "Leaving ingress-nginx installed because it is not marked as bootstrap-managed."
    return
  fi

  kubectl delete -f "${DEFAULT_INGRESS_NGINX_MANIFEST_URL}" --ignore-not-found >/dev/null || true
  wait_for_namespace_deletion "${INGRESS_NAMESPACE}"

  echo "Removed ingress-nginx"
}

echo "Deleting namespace ${K8S_NAMESPACE}"
delete_namespace_and_wait

if kubectl get svc "${INGRESS_SERVICE}" --namespace "${INGRESS_NAMESPACE}" >/dev/null 2>&1; then
  reset_docker_desktop_exposure
fi

if kubectl get namespace "${INGRESS_NAMESPACE}" >/dev/null 2>&1; then
  delete_ingress_nginx_if_managed
fi

if [[ "${DELETE_RENDERED}" == "true" ]]; then
  rm -rf "${RENDER_DIR}"
fi

if [[ "${DELETE_ENV_FILE}" == "true" ]]; then
  rm -f "${ENV_FILE}"
fi

echo "Teardown complete."

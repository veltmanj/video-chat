#!/usr/bin/env bash
set -euo pipefail

# Remove the stack namespace and optionally undo the shared ingress tweaks that
# bootstrap.sh applied for local Docker Desktop clusters.

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
  --verbose                     Show narrated step-by-step output (default)
  --quiet                       Reduce output to command errors and the final summary
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
select_kube_context "${VIDEOCHAT_KUBECTL_CONTEXT:-docker-desktop}"
require_kube_cluster_access

DEFAULT_INGRESS_NGINX_MANIFEST_URL="${K8S_INGRESS_NGINX_MANIFEST_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.2/deploy/static/provider/cloud/deploy.yaml}"
INGRESS_NAMESPACE="${K8S_INGRESS_NGINX_NAMESPACE:-ingress-nginx}"
INGRESS_SERVICE="${K8S_INGRESS_NGINX_SERVICE:-ingress-nginx-controller}"
NAMESPACE_DELETE_TIMEOUT_SECONDS="${K8S_NAMESPACE_DELETE_TIMEOUT_SECONDS:-120}"

wait_for_namespace_deletion() {
  local namespace="$1"
  local remaining="${NAMESPACE_DELETE_TIMEOUT_SECONDS}"
  local finalize_payload

  while k8s_namespace_exists "${namespace}"; do
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
  if ! k8s_namespace_exists "${K8S_NAMESPACE}"; then
    log_info "Namespace ${K8S_NAMESPACE} does not exist."
    return
  fi

  run_with_log_mode kubectl delete namespace "${K8S_NAMESPACE}" --wait=false
  wait_for_namespace_deletion "${K8S_NAMESPACE}"
}

# Docker Desktop exposure rewrites the ingress-nginx Service ports. Restore the
# original ports before removing ingress so the shared cluster returns to a sane
# default state.
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

  if [[ -z "${managed}" ]]; then
    log_info "Docker Desktop ingress exposure annotations are not present."
    return
  fi

  run_with_log_mode kubectl patch svc "${INGRESS_SERVICE}" \
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
    }"

  log_info "Reset Docker Desktop ingress exposure on ${INGRESS_NAMESPACE}/${INGRESS_SERVICE}."
}

ingress_installed_by_bootstrap() {
  local managed

  managed="$(kubectl get namespace "${INGRESS_NAMESPACE}" -o jsonpath='{.metadata.annotations.videochat\.nextend/installed-by-bootstrap}' 2>/dev/null || true)"
  [[ "${managed}" == "true" ]]
}

delete_ingress_nginx_if_managed() {
  if [[ "${DELETE_INGRESS_NGINX}" != "true" ]] && ! ingress_installed_by_bootstrap; then
    log_skip "ingress-nginx removal because it is not marked as bootstrap-managed."
    return
  fi

  run_with_log_mode kubectl delete -f "${DEFAULT_INGRESS_NGINX_MANIFEST_URL}" --ignore-not-found || true
  wait_for_namespace_deletion "${INGRESS_NAMESPACE}"

  log_info "Removed ingress-nginx."
}

# Teardown order:
# - delete the app namespace first
# - then restore ingress service state
# - finally remove ingress-nginx only when bootstrap owned it
log_info "Context: $(current_kube_context)"
log_info "Namespace: ${K8S_NAMESPACE}"
log_step "Deleting application namespace"
delete_namespace_and_wait

if k8s_service_exists "${INGRESS_NAMESPACE}" "${INGRESS_SERVICE}"; then
  log_step "Resetting Docker Desktop ingress service"
  reset_docker_desktop_exposure
fi

if k8s_namespace_exists "${INGRESS_NAMESPACE}"; then
  log_step "Handling ingress-nginx cleanup"
  delete_ingress_nginx_if_managed
fi

if [[ "${DELETE_RENDERED}" == "true" ]]; then
  log_step "Removing rendered manifest cache"
  rm -rf "${RENDER_DIR}"
else
  log_skip "rendered manifest deletion because --delete-rendered was not provided."
fi

if [[ "${DELETE_ENV_FILE}" == "true" ]]; then
  log_step "Removing local env file"
  rm -f "${ENV_FILE}"
else
  log_skip "local env file deletion because --delete-env-file was not provided."
fi

echo "Teardown complete for namespace ${K8S_NAMESPACE} on context $(current_kube_context)."

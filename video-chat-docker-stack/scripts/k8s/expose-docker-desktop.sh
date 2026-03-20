#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/expose-docker-desktop.sh [options]

Patch ingress-nginx on Docker Desktop to listen on localhost-friendly ports.

Options:
  --env <path>    Use a specific env file instead of k8s/k8s.env
  --verbose       Show narrated step-by-step output (default)
  --quiet         Reduce output to command errors and the final summary
  --help          Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
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

HTTP_PORT="${K8S_DOCKER_DESKTOP_HTTP_PORT:-8080}"
HTTPS_PORT="${K8S_DOCKER_DESKTOP_HTTPS_PORT:-8443}"
INGRESS_NAMESPACE="${K8S_INGRESS_NGINX_NAMESPACE:-ingress-nginx}"
INGRESS_SERVICE="${K8S_INGRESS_NGINX_SERVICE:-ingress-nginx-controller}"
MANAGED_ANNOTATION_KEY="videochat.nextend/docker-desktop-expose"
ORIGINAL_HTTP_ANNOTATION_KEY="videochat.nextend/original-http-port"
ORIGINAL_HTTPS_ANNOTATION_KEY="videochat.nextend/original-https-port"

host_from_ingress() {
  kubectl get ingress -A -o jsonpath='{.items[0].spec.rules[0].host}' 2>/dev/null || true
}

service_annotation() {
  local key="$1"

  kubectl get svc "${INGRESS_SERVICE}" \
    --namespace "${INGRESS_NAMESPACE}" \
    -o "jsonpath={.metadata.annotations.${key}}" 2>/dev/null || true
}

HOST="${K8S_HOST:-$(host_from_ingress)}"
ORIGINAL_HTTP_PORT="$(service_annotation 'videochat\.nextend/original-http-port')"
ORIGINAL_HTTPS_PORT="$(service_annotation 'videochat\.nextend/original-https-port')"

if [[ -z "${ORIGINAL_HTTP_PORT}" ]]; then
  ORIGINAL_HTTP_PORT=80
fi

if [[ -z "${ORIGINAL_HTTPS_PORT}" ]]; then
  ORIGINAL_HTTPS_PORT=443
fi

[[ -n "${HOST}" ]] || fail "Could not determine an ingress host. Export K8S_HOST or create the ingress objects first."

log_step "Patching Docker Desktop ingress service"
run_with_log_mode kubectl patch svc "${INGRESS_SERVICE}" \
  --namespace "${INGRESS_NAMESPACE}" \
  --type='merge' \
  -p "{
    \"spec\": {
      \"ports\": [
        {
          \"appProtocol\": \"http\",
          \"name\": \"http\",
          \"port\": ${HTTP_PORT},
          \"protocol\": \"TCP\",
          \"targetPort\": \"http\"
        },
        {
          \"appProtocol\": \"https\",
          \"name\": \"https\",
          \"port\": ${HTTPS_PORT},
          \"protocol\": \"TCP\",
          \"targetPort\": \"https\"
        }
      ]
    }
  }"

log_step "Recording original ingress ports in annotations"
run_with_log_mode kubectl annotate svc "${INGRESS_SERVICE}" \
  --namespace "${INGRESS_NAMESPACE}" \
  "${MANAGED_ANNOTATION_KEY}=true" \
  "${ORIGINAL_HTTP_ANNOTATION_KEY}=${ORIGINAL_HTTP_PORT:-80}" \
  "${ORIGINAL_HTTPS_ANNOTATION_KEY}=${ORIGINAL_HTTPS_PORT:-443}" \
  --overwrite

echo "Docker Desktop ingress exposed on:"
echo "  https://${HOST}:${HTTPS_PORT}/"
echo
if [[ "${LOG_LEVEL}" != "quiet" ]]; then
  echo "Service status:"
  kubectl get svc "${INGRESS_SERVICE}" --namespace "${INGRESS_NAMESPACE}" -o wide
fi

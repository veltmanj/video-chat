#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

require_command kubectl
load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"

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

kubectl patch svc "${INGRESS_SERVICE}" \
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
  }" >/dev/null

kubectl annotate svc "${INGRESS_SERVICE}" \
  --namespace "${INGRESS_NAMESPACE}" \
  "${MANAGED_ANNOTATION_KEY}=true" \
  "${ORIGINAL_HTTP_ANNOTATION_KEY}=${ORIGINAL_HTTP_PORT:-80}" \
  "${ORIGINAL_HTTPS_ANNOTATION_KEY}=${ORIGINAL_HTTPS_PORT:-443}" \
  --overwrite >/dev/null

echo "Docker Desktop ingress exposed on:"
echo "  https://${HOST}:${HTTPS_PORT}/"
echo
echo "Service status:"
kubectl get svc "${INGRESS_SERVICE}" --namespace "${INGRESS_NAMESPACE}" -o wide

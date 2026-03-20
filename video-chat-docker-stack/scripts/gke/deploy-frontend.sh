#!/usr/bin/env bash
set -euo pipefail

# Fast-path rollout for frontend-only changes. This keeps the running cluster,
# database, broker, and backoffice untouched while still updating runtime config
# and the frontend Deployment in Kubernetes.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command docker
require_command kubectl
require_command git

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/deploy-frontend.sh [options]

Build, push, and roll out only the frontend image on the existing GKE cluster.

Options:
  --env <path>         Use a specific env file instead of k8s/k8s.env
  --tag <value>        Use a specific image tag instead of generating one
  --runmode <value>    Frontend app mode: prod or dev (default: prod)
  --skip-build         Reuse an already-pushed frontend image tag
  --timeout <value>    kubectl rollout timeout (default: 5m)
  --help               Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
IMAGE_TAG=""
RUN_MODE="production"
SKIP_BUILD=false
ROLLOUT_TIMEOUT="5m"

normalize_runmode() {
  case "$1" in
    prod|production)
      printf 'production\n'
      ;;
    dev|development)
      printf 'development\n'
      ;;
    *)
      fail "Unsupported --runmode value: $1 (expected prod or dev)"
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --tag)
      [[ $# -ge 2 ]] || fail "--tag requires a value"
      IMAGE_TAG="$2"
      shift 2
      ;;
    --runmode)
      [[ $# -ge 2 ]] || fail "--runmode requires a value"
      RUN_MODE="$(normalize_runmode "$2")"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --timeout)
      [[ $# -ge 2 ]] || fail "--timeout requires a value"
      ROLLOUT_TIMEOUT="$2"
      shift 2
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

derive_image_tag() {
  local short_sha timestamp

  short_sha="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo manual)"
  timestamp="$(date -u +%Y%m%d%H%M%S)"
  printf 'frontend-%s-%s\n' "${timestamp}" "${short_sha}"
}

TEMP_ENV_FILE="$(mktemp)"
TEMP_APP_CONFIG="$(mktemp)"
cleanup() {
  rm -f "${TEMP_ENV_FILE}" "${TEMP_APP_CONFIG}"
}
trap cleanup EXIT

# Render app-config from a temporary env file so the rollout can override only
# VIDEOCHAT_APP_MODE without mutating the checked-in k8s.env permanently.
cp "${ENV_FILE}" "${TEMP_ENV_FILE}"
write_env_value "${TEMP_ENV_FILE}" "VIDEOCHAT_APP_MODE" "${RUN_MODE}"

load_k8s_env "${TEMP_ENV_FILE}"

[[ -n "${K8S_IMAGE_REGISTRY:-}" ]] || fail "K8S_IMAGE_REGISTRY must be set in ${ENV_FILE}"

if [[ -z "${IMAGE_TAG}" ]]; then
  IMAGE_TAG="$(derive_image_tag)"
fi

FRONTEND_IMAGE="${K8S_IMAGE_REGISTRY%/}/video-chat-angular:${IMAGE_TAG}"
export FRONTEND_IMAGE

CURRENT_IMAGE="$(
  kubectl get deployment frontend \
    --namespace "${K8S_NAMESPACE}" \
    -o jsonpath='{.spec.template.spec.containers[0].image}'
)"
CURRENT_RUN_MODE="$(
  kubectl get configmap app-config \
    --namespace "${K8S_NAMESPACE}" \
    -o jsonpath='{.data.VIDEOCHAT_APP_MODE}' 2>/dev/null || true
)"

# Keep the ConfigMap in sync even when the container image itself does not
# change between rollouts.
render_template "${K8S_DIR}/templates/10-app-config.yaml.tmpl" "${TEMP_APP_CONFIG}"
kubectl apply -f "${TEMP_APP_CONFIG}"

if [[ "${SKIP_BUILD}" == "false" ]]; then
  docker build --platform "${K8S_BUILD_PLATFORM:-linux/amd64}" -t "${FRONTEND_IMAGE}" "${ROOT_DIR}/../video-chat-angular"
  docker push "${FRONTEND_IMAGE}"
fi

kubectl set image \
  --namespace "${K8S_NAMESPACE}" \
  deployment/frontend \
  frontend="${FRONTEND_IMAGE}"

if [[ "${CURRENT_IMAGE}" == "${FRONTEND_IMAGE}" && "${CURRENT_RUN_MODE}" != "${RUN_MODE}" ]]; then
  kubectl rollout restart \
    --namespace "${K8S_NAMESPACE}" \
    deployment/frontend
fi

# Block until Kubernetes reports a healthy replacement pod so the caller gets a
# clear success/failure signal from this single command.
kubectl rollout status \
  --namespace "${K8S_NAMESPACE}" \
  deployment/frontend \
  --timeout="${ROLLOUT_TIMEOUT}"

printf 'Frontend image deployed: %s\n' "${FRONTEND_IMAGE}"
printf 'Frontend run mode: %s\n' "${RUN_MODE}"
printf 'Namespace: %s\n' "${K8S_NAMESPACE}"
printf 'Host: https://%s/\n' "${K8S_HOST}"

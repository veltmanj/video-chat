#!/usr/bin/env bash
set -euo pipefail

# Render, apply, and wait for the Kubernetes bundle in one command.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/apply.sh [options]

Render the manifests, apply them to the cluster, and wait for the stack.

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

log_step "Rendering Kubernetes manifests"
run_with_log_mode "${SCRIPT_DIR}/render.sh" --env "${ENV_FILE}" "--${LOG_LEVEL}"

if [[ "${K8S_EXPOSURE_MODE}" == "ingress-nginx" ]] && ! kubectl get secret "${K8S_TLS_SECRET_NAME}" --namespace "${K8S_NAMESPACE}" >/dev/null 2>&1; then
  echo "TLS secret ${K8S_TLS_SECRET_NAME} is not present in namespace ${K8S_NAMESPACE} yet." >&2
  echo "Create it first with ./scripts/k8s/create-self-signed-tls.sh or your preferred certificate workflow." >&2
fi

log_step "Applying manifests to namespace ${K8S_NAMESPACE}"
run_with_log_mode kubectl apply -f "${RENDER_DIR}"

log_step "Waiting for workloads to become ready"
run_with_log_mode "${SCRIPT_DIR}/wait.sh" --env "${ENV_FILE}" "--${LOG_LEVEL}"

printf 'Applied Kubernetes bundle in namespace %s\n' "${K8S_NAMESPACE}"

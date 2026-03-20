#!/usr/bin/env bash
set -euo pipefail

# Wait for all stack workloads to complete their Kubernetes rollouts.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/wait.sh [options]

Wait for the stack workloads to report rollout success.

Options:
  --env <path>        Use a specific env file instead of k8s/k8s.env
  --timeout <value>   Rollout timeout per workload (default: 300s)
  --verbose           Show narrated step-by-step output (default)
  --quiet             Reduce output to command errors and the final summary
  --help              Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
ROLLOUT_TIMEOUT="300s"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --timeout)
      [[ $# -ge 2 ]] || fail "--timeout requires a value"
      ROLLOUT_TIMEOUT="$2"
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

wait_for_rollout() {
  local resource="$1"
  log_info "Waiting for ${resource}"
  run_with_log_mode kubectl rollout status "${resource}" --namespace "${K8S_NAMESPACE}" --timeout="${ROLLOUT_TIMEOUT}"
}

log_step "Waiting for core workloads"
wait_for_rollout deployment/frontend
wait_for_rollout deployment/broker
wait_for_rollout deployment/backoffice
wait_for_rollout deployment/minio
wait_for_rollout deployment/blackbox
wait_for_rollout deployment/prometheus
wait_for_rollout deployment/loki
wait_for_rollout deployment/grafana
wait_for_rollout daemonset/promtail
wait_for_rollout statefulset/social-db

if [[ "${K8S_EXPOSURE_MODE}" == "gke-gateway" ]]; then
  log_step "Inspecting Gateway resources"
  run_with_log_mode kubectl get \
    gateway.gateway.networking.k8s.io,\
httproute.gateway.networking.k8s.io,\
healthcheckpolicies.networking.gke.io,\
gcpbackendpolicies.networking.gke.io \
    --namespace "${K8S_NAMESPACE}" || true
else
  log_step "Inspecting ingress resources"
  run_with_log_mode kubectl get ingress --namespace "${K8S_NAMESPACE}" || true
fi

printf 'Stack is ready in namespace %s\n' "${K8S_NAMESPACE}"

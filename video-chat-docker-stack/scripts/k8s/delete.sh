#!/usr/bin/env bash
set -euo pipefail

# Delete only the application namespace without the broader teardown logic.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/delete.sh [options]

Delete the application namespace only.

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
require_kube_cluster_access
log_info "Context: $(current_kube_context)"
log_step "Deleting namespace ${K8S_NAMESPACE}"
run_with_log_mode kubectl delete namespace "${K8S_NAMESPACE}" --ignore-not-found
printf 'Delete requested for namespace %s\n' "${K8S_NAMESPACE}"

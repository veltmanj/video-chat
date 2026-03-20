#!/usr/bin/env bash
set -euo pipefail

# Keep Grafana private in-cluster and expose it only through a local
# kubectl port-forward on the operator's machine.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command kubectl
require_command base64

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/access-grafana.sh [options]

Print the Grafana admin credentials and start a local port-forward to the
private Grafana service in GKE.

Options:
  --env <path>    Use a specific env file instead of k8s/k8s.env
  --port <value>  Local port to bind (default: 3000)
  --help          Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
LOCAL_PORT="3000"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || fail "--port requires a value"
      LOCAL_PORT="$2"
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

load_k8s_env "${ENV_FILE}"

# The admin password is stored once in the shared app secret bundle alongside
# the rest of the stack credentials.
GRAFANA_PASSWORD="$(
  kubectl -n "${K8S_NAMESPACE}" get secret app-secrets \
    -o jsonpath='{.data.GRAFANA_ADMIN_PASSWORD}' | base64 --decode
)"

cat <<EOF
Grafana URL: http://127.0.0.1:${LOCAL_PORT}
Username: ${GRAFANA_ADMIN_USER}
Password: ${GRAFANA_PASSWORD}

Starting port-forward:
kubectl -n ${K8S_NAMESPACE} port-forward svc/grafana ${LOCAL_PORT}:3000
EOF

exec kubectl -n "${K8S_NAMESPACE}" port-forward svc/grafana "${LOCAL_PORT}:3000"

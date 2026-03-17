#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"
"${SCRIPT_DIR}/render.sh" "${1:-${ENV_FILE_DEFAULT}}"

if ! kubectl get secret "${K8S_TLS_SECRET_NAME}" --namespace "${K8S_NAMESPACE}" >/dev/null 2>&1; then
  echo "TLS secret ${K8S_TLS_SECRET_NAME} is not present in namespace ${K8S_NAMESPACE} yet." >&2
  echo "Create it first with ./scripts/k8s/create-self-signed-tls.sh or your preferred certificate workflow." >&2
fi

kubectl apply -f "${RENDER_DIR}"
"${SCRIPT_DIR}/wait.sh" "${1:-${ENV_FILE_DEFAULT}}"

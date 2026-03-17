#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"
kubectl delete namespace "${K8S_NAMESPACE}" --ignore-not-found

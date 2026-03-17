#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"

kubectl rollout status deployment/frontend --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/broker --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/backoffice --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/minio --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/blackbox --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/prometheus --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/loki --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status deployment/grafana --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status daemonset/promtail --namespace "${K8S_NAMESPACE}" --timeout=300s
kubectl rollout status statefulset/social-db --namespace "${K8S_NAMESPACE}" --timeout=300s

printf 'Stack is ready in namespace %s\n' "${K8S_NAMESPACE}"

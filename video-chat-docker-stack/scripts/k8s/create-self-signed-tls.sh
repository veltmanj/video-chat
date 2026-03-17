#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl
require_command openssl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

openssl req \
  -x509 \
  -nodes \
  -newkey rsa:2048 \
  -days 365 \
  -keyout "${tmp_dir}/tls.key" \
  -out "${tmp_dir}/tls.crt" \
  -subj "/CN=${K8S_HOST}" \
  -addext "subjectAltName=DNS:${K8S_HOST}" >/dev/null 2>&1

kubectl create namespace "${K8S_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret tls "${K8S_TLS_SECRET_NAME}" \
  --namespace "${K8S_NAMESPACE}" \
  --cert="${tmp_dir}/tls.crt" \
  --key="${tmp_dir}/tls.key" \
  --dry-run=client \
  -o yaml | kubectl apply -f -

printf 'Created self-signed TLS secret %s in namespace %s for host %s\n' \
  "${K8S_TLS_SECRET_NAME}" \
  "${K8S_NAMESPACE}" \
  "${K8S_HOST}"

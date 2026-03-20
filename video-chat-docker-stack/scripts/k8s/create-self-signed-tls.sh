#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl
require_command openssl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/create-self-signed-tls.sh [options]

Create or refresh a locally trusted self-signed TLS secret for ingress-nginx.

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

CA_DIR="${K8S_DIR}/.ca"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

# Generate a local CA once and reuse it so the keychain trust persists across rebuilds.
log_step "Preparing local certificate authority"
if [[ ! -f "${CA_DIR}/ca.key" || ! -f "${CA_DIR}/ca.crt" ]]; then
  mkdir -p "${CA_DIR}"
  openssl req \
    -x509 \
    -nodes \
    -newkey rsa:2048 \
    -days 3650 \
    -keyout "${CA_DIR}/ca.key" \
    -out "${CA_DIR}/ca.crt" \
    -subj "/CN=videochat-local-ca" >/dev/null 2>&1
  echo "Generated new local CA in ${CA_DIR}"

  if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "Adding CA to macOS login keychain as a trusted root"
    security add-trusted-cert -r trustRoot -k "$HOME/Library/Keychains/login.keychain-db" "${CA_DIR}/ca.crt" 2>/dev/null \
      && echo "CA trusted in login keychain" \
      || echo "Warning: could not add CA to keychain (non-fatal)"
  fi
else
  log_info "Reusing local CA from ${CA_DIR}."
fi

# Generate a server key + CSR and sign it with the local CA.
log_step "Generating server certificate for ${K8S_HOST}"
openssl req \
  -nodes \
  -newkey rsa:2048 \
  -keyout "${tmp_dir}/tls.key" \
  -out "${tmp_dir}/tls.csr" \
  -subj "/CN=${K8S_HOST}" >/dev/null 2>&1

cat > "${tmp_dir}/ext.cnf" <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:${K8S_HOST}
EOF

openssl x509 \
  -req \
  -in "${tmp_dir}/tls.csr" \
  -CA "${CA_DIR}/ca.crt" \
  -CAkey "${CA_DIR}/ca.key" \
  -CAcreateserial \
  -days 365 \
  -extfile "${tmp_dir}/ext.cnf" \
  -out "${tmp_dir}/tls.crt" >/dev/null 2>&1

log_step "Applying TLS secret ${K8S_TLS_SECRET_NAME}"
kubectl create namespace "${K8S_NAMESPACE}" --dry-run=client -o yaml | run_with_log_mode kubectl apply -f -
kubectl create secret tls "${K8S_TLS_SECRET_NAME}" \
  --namespace "${K8S_NAMESPACE}" \
  --cert="${tmp_dir}/tls.crt" \
  --key="${tmp_dir}/tls.key" \
  --dry-run=client \
  -o yaml | run_with_log_mode kubectl apply -f -

printf 'Created TLS secret %s in namespace %s for host %s (signed by local CA)\n' \
  "${K8S_TLS_SECRET_NAME}" \
  "${K8S_NAMESPACE}" \
  "${K8S_HOST}"

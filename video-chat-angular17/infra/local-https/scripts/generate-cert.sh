#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <LAN_IP>"
  echo "Example: $0 <IP-ADDRESS>"
  exit 1
fi

LAN_IP="$1"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CERT_DIR="${ROOT_DIR}/certs"

mkdir -p "${CERT_DIR}"

if ! command -v mkcert >/dev/null 2>&1; then
  echo "mkcert is required. Install it first:"
  echo "  brew install mkcert nss"
  exit 1
fi

echo "Generating certificate for: ${LAN_IP}, localhost, 127.0.0.1, ::1"
mkcert -install
mkcert \
  -cert-file "${CERT_DIR}/videochat-lan.pem" \
  -key-file "${CERT_DIR}/videochat-lan-key.pem" \
  "${LAN_IP}" localhost 127.0.0.1 ::1

echo "Certificate generated:"
echo "  ${CERT_DIR}/videochat-lan.pem"
echo "  ${CERT_DIR}/videochat-lan-key.pem"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

if [[ ! -f "${ROOT_DIR}/certs/videochat-lan.pem" ]] || [[ ! -f "${ROOT_DIR}/certs/videochat-lan-key.pem" ]]; then
  echo "Missing TLS certificate files in ${ROOT_DIR}/certs."
  echo "Run first:"
  echo "  ${SCRIPT_DIR}/generate-cert.sh <LAN_IP>"
  exit 1
fi

cd "${ROOT_DIR}"
docker compose up -d
docker compose ps

echo
echo "HTTPS reverse proxy is running at: https://<LAN_IP>:8443"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_FILE="${ROOT_DIR}/caddy-local-root-ca.crt"

cd "${ROOT_DIR}"

docker compose exec -T caddy sh -lc "cat /data/caddy/pki/authorities/local/root.crt" > "${OUTPUT_FILE}"

echo "Exported Caddy local CA certificate:"
echo "  ${OUTPUT_FILE}"
echo
echo "Install and trust this cert on your iPad for HTTPS camera access."

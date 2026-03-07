#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_FILE="${ROOT_DIR}/${CADDY_LOCAL_CA_FILENAME:-caddy-local-root-ca.crt}"

cd "${ROOT_DIR}"

docker compose exec -T caddy sh -lc "cat /data/caddy/pki/authorities/local/root.crt" > "${OUTPUT_FILE}"

echo 'Exported Caddy local CA certificate:'
printf '  %s
' "${OUTPUT_FILE}"
echo
echo 'Install and trust this certificate on clients that need camera or websocket access over HTTPS.'

#!/usr/bin/env bash
set -euo pipefail

# Builds and starts the full local deployment stack.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"

docker compose up -d --build

echo
printf 'Configured VIDEOCHAT_HOST: %s
' "${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' .env 2>/dev/null | cut -d= -f2- || echo localhost)}"
echo 'Run ./scripts/check.sh to verify health and routing.'
echo 'If this is a first run on a new device, export/install the Caddy local CA certificate:'
echo '  ./scripts/export-caddy-root-ca.sh'

docker compose ps

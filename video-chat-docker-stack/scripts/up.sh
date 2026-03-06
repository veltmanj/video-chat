#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"
docker compose up -d --build
docker compose ps

echo
echo "Configured VIDEOCHAT_HOST: ${VIDEOCHAT_HOST:-localhost}"
echo "Open: https://<LAN_IP>"
echo "If this is first run, export/install the Caddy local CA certificate:"
echo "  ./scripts/export-caddy-root-ca.sh"

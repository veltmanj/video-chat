#!/usr/bin/env bash
set -euo pipefail

# Builds and starts the stack with frontend developer diagnostics enabled.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"

VIDEOCHAT_APP_MODE=development docker compose up -d --build

echo
printf 'Configured VIDEOCHAT_HOST: %s\n' "${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' .env 2>/dev/null | cut -d= -f2- || echo localhost)}"
echo 'Stack started in development mode.'
echo 'The login page will show OAuth setup diagnostics.'
echo 'Run ./scripts/check.sh to verify health and routing.'

#!/usr/bin/env bash
set -euo pipefail

# Builds and starts the full local deployment stack.

usage() {
  cat <<'EOF'
Usage: ./scripts/up.sh

Build and start the full local Docker Compose stack in the background.

Arguments:
  -h, --help    Show this help text.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 0 ]]; then
  usage >&2
  exit 1
fi

cd "${ROOT_DIR}"

docker compose up -d --build

echo
printf 'Configured VIDEOCHAT_HOST: %s
' "${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' .env 2>/dev/null | cut -d= -f2- || echo localhost)}"
echo 'Run ./scripts/check.sh to verify health and routing.'
echo 'If this is a first run on a new device, export/install the Caddy local CA certificate:'
echo '  ./scripts/export-caddy-root-ca.sh'

docker compose ps

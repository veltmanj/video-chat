#!/usr/bin/env bash
set -euo pipefail

# Validates the deployment configuration before bringing the stack up.

usage() {
  cat <<'EOF'
Usage: ./scripts/validate.sh

Validate the local Docker Compose and Caddy configuration before startup.

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

echo '== docker compose config =='
docker compose config >/dev/null

echo '== caddy validate =='
docker run --rm   -v "${ROOT_DIR}/Caddyfile:/etc/caddy/Caddyfile:ro"   "${CADDY_IMAGE:-caddy:2.10.2}"   caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo 'Validation succeeded.'

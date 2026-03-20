#!/usr/bin/env bash
set -euo pipefail

# Builds and starts the stack with frontend developer diagnostics enabled.

usage() {
  cat <<'EOF'
Usage: ./scripts/up-dev.sh

Build and start the local Docker Compose stack in development mode, using the
isolated dev social database and seeded sample profiles.

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

VIDEOCHAT_APP_MODE=development \
SOCIAL_DB_NAME="${SOCIAL_DB_DEV_NAME:-videochat_dev}" \
SOCIAL_DB_CONTAINER_NAME="${SOCIAL_DB_DEV_CONTAINER_NAME:-videochat-social-db-dev}" \
SOCIAL_DB_VOLUME_NAME="${SOCIAL_DB_DEV_VOLUME_NAME:-social-db-dev-data}" \
SOCIAL_DB_SEED_CONTAINER_NAME="${SOCIAL_DB_DEV_SEED_CONTAINER_NAME:-videochat-social-db-seed-dev}" \
SOCIAL_DB_SEED_ENABLED="${SOCIAL_DB_SEED_ENABLED:-true}" \
docker compose --profile dev-seed up -d --build

echo
printf 'Configured VIDEOCHAT_HOST: %s\n' "${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' .env 2>/dev/null | cut -d= -f2- || echo localhost)}"
echo 'Stack started in development mode.'
echo 'The social database uses an isolated dev database and seeded sample profiles.'
echo 'The login page will show OAuth setup diagnostics.'
echo 'Run ./scripts/check.sh to verify health and routing.'

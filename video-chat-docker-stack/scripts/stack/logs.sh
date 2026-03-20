#!/usr/bin/env bash
set -euo pipefail

# Streams recent docker compose logs. Pass a service name to narrow the output.

usage() {
  cat <<'EOF'
Usage: ./scripts/logs.sh [service-name]

Stream recent Docker Compose logs for the whole stack or for one service.

Arguments:
  service-name   Optional Docker Compose service to filter to.
  -h, --help     Show this help text.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 1
fi

SERVICE="${1:-}"

cd "${ROOT_DIR}"

if [[ -n "${SERVICE}" ]]; then
  docker compose logs -f --tail=200 "${SERVICE}"
else
  docker compose logs -f --tail=200
fi

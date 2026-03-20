#!/usr/bin/env bash
set -euo pipefail

# Stops the local stack without removing source-controlled configuration.

usage() {
  cat <<'EOF'
Usage: ./scripts/down.sh

Stop the local Docker Compose stack without deleting project files.

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
docker compose down

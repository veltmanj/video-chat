#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SERVICE="${1:-}"

cd "${ROOT_DIR}"

if [[ -n "${SERVICE}" ]]; then
  docker compose logs -f --tail=200 "${SERVICE}"
else
  docker compose logs -f --tail=200
fi

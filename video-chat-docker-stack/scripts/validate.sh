#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"

echo '== docker compose config =='
docker compose config >/dev/null

echo '== caddy validate =='
docker run --rm   -v "${ROOT_DIR}/Caddyfile:/etc/caddy/Caddyfile:ro"   "${CADDY_IMAGE:-caddy:2.10.2}"   caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo 'Validation succeeded.'

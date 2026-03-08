#!/usr/bin/env bash
set -euo pipefail

# Sanity-checks the running stack from the host machine.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# Reuse the configured deployment host and force curl back to localhost so the checks work even when
# DNS or client routing is not fully in place on the machine running the script.
HOST_VALUE="${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || echo localhost)}"
HTTPS_RESOLVE=(--resolve "${HOST_VALUE}:443:127.0.0.1")
HTTP_RESOLVE=(--resolve "${HOST_VALUE}:80:127.0.0.1")

cd "${ROOT_DIR}"

echo '== docker compose ps =='
docker compose ps

echo
echo '== broker health (inside container) =='
docker compose exec -T broker curl -fsS http://127.0.0.1:9898/actuator/health || true

echo

echo '== backoffice health (inside container) =='
docker compose exec -T backoffice curl -fsS http://127.0.0.1:7901/actuator/health || true

echo

echo '== root response through local Caddy =='
curl -skI --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" "https://${HOST_VALUE}/" || true

echo

echo '== backoffice API via local Caddy =='
curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" "https://${HOST_VALUE}/backoffice-api/api/rooms" || true

echo

echo '== local CA download via local Caddy =='
curl -skI --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" "https://${HOST_VALUE}/local-ca.crt" || true
curl -sI --max-time 10 "${HTTP_RESOLVE[@]}" "http://${HOST_VALUE}/local-ca.crt" || true

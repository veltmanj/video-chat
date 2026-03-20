#!/usr/bin/env bash
set -euo pipefail

# Sanity-checks the running stack from the host machine.

usage() {
  cat <<'EOF'
Usage: ./scripts/check.sh

Run host-side sanity checks against the running local stack, including Docker
Compose status, backend health endpoints, edge routing, Prometheus, Loki, and Grafana.

Arguments:
  -h, --help    Show this help text.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT_DIR}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 0 ]]; then
  usage >&2
  exit 1
fi

# Reuse the configured deployment host and force curl back to localhost so the checks work even when
# DNS or client routing is not fully in place on the machine running the script.
HOST_VALUE="${VIDEOCHAT_HOST:-$(grep '^VIDEOCHAT_HOST=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || echo localhost)}"
HTTPS_RESOLVE=(--resolve "${HOST_VALUE}:443:127.0.0.1")
HTTP_RESOLVE=(--resolve "${HOST_VALUE}:80:127.0.0.1")
PROMETHEUS_PORT="${PROMETHEUS_HOST_PORT:-$(grep '^PROMETHEUS_HOST_PORT=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || echo 9090)}"
GRAFANA_PORT="${GRAFANA_HOST_PORT:-$(grep '^GRAFANA_HOST_PORT=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || echo 3000)}"
GRAFANA_USER="${GRAFANA_ADMIN_USER:-$(grep '^GRAFANA_ADMIN_USER=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || echo admin)}"

read_runtime_secret() {
  local secret_file="$1"
  docker compose exec -T grafana sh -lc "cat '${secret_file}'" 2>/dev/null || true
}

GRAFANA_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-$(grep '^GRAFANA_ADMIN_PASSWORD=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)}"
if [ -z "${GRAFANA_PASSWORD}" ]; then
  GRAFANA_PASSWORD="$(grep '^VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD=' "${ROOT_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
fi
if [ -z "${GRAFANA_PASSWORD}" ]; then
  GRAFANA_PASSWORD="$(read_runtime_secret "/vault/runtime/grafana-admin-password")"
fi

echo '== docker compose ps =='
docker compose ps

echo
echo '== vault health (inside container) =='
docker compose exec -T vault wget -q -O - http://127.0.0.1:8200/v1/sys/health || true

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

echo
echo '== prometheus readiness =='
curl -fsS --max-time 10 "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" || true

echo
echo '== prometheus targets =='
curl -fsS --max-time 10 "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets" || true

echo
echo '== loki labels =='
docker run --rm --network videochat-network curlimages/curl:8.8.0 -fsS http://loki:3100/loki/api/v1/labels || true

echo
echo '== grafana health =='
curl -fsS --max-time 10 "http://127.0.0.1:${GRAFANA_PORT}/api/health" || true

echo
echo '== grafana dashboards =='
curl -fsS --max-time 10 -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" \
  "http://127.0.0.1:${GRAFANA_PORT}/api/search?query=Video%20Chat" || true

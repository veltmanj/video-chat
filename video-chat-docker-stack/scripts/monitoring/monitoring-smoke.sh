#!/usr/bin/env bash
set -euo pipefail

# Verifies the local monitoring stack end-to-end:
# Prometheus targets, Grafana dashboards, Loki/Promtail log flow, and a fresh backoffice access-log event.

usage() {
  cat <<'EOF'
Usage: ./scripts/monitoring-smoke.sh

Run an end-to-end smoke test of the local monitoring stack:
Prometheus, Grafana, Loki, Promtail, and fresh backoffice telemetry.

Arguments:
  -h, --help    Show this help text.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
PROMTAIL_CURL_IMAGE="${PROMTAIL_CURL_IMAGE:-curlimages/curl:8.8.0}"
STACK_NETWORK="${STACK_NETWORK:-videochat-network}"

cd "${ROOT_DIR}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 0 ]]; then
  usage >&2
  exit 1
fi

read_env_value() {
  local key="$1"
  local value
  value="$(grep -E "^${key}=" .env 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
  printf '%s' "${value}"
}

read_runtime_secret() {
  local secret_file="$1"
  docker compose exec -T grafana sh -lc "cat '${secret_file}'" 2>/dev/null || true
}

fail() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

retry_until() {
  local attempts="$1"
  local sleep_seconds="$2"
  local description="$3"
  shift 3

  local attempt=1
  until "$@"; do
    if (( attempt >= attempts )); then
      fail "${description} failed after ${attempts} attempts."
    fi

    echo "waiting for ${description} (${attempt}/${attempts})..."
    sleep "${sleep_seconds}"
    attempt=$((attempt + 1))
  done
}

docker_net_curl() {
  docker run --rm --network "${STACK_NETWORK}" "${PROMTAIL_CURL_IMAGE}" "$@"
}

PROMETHEUS_PORT="${PROMETHEUS_HOST_PORT:-$(read_env_value "PROMETHEUS_HOST_PORT")}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
GRAFANA_PORT="${GRAFANA_HOST_PORT:-$(read_env_value "GRAFANA_HOST_PORT")}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"
GRAFANA_USER="${GRAFANA_ADMIN_USER:-$(read_env_value "GRAFANA_ADMIN_USER")}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-$(read_env_value "GRAFANA_ADMIN_PASSWORD")}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-$(read_env_value "VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD")}"
if [ -z "${GRAFANA_PASSWORD}" ]; then
  GRAFANA_PASSWORD="$(read_runtime_secret "/vault/runtime/grafana-admin-password")"
fi

require_command curl
require_command jq
require_command docker
require_command python3

docker compose ps >/dev/null
docker network inspect "${STACK_NETWORK}" >/dev/null 2>&1 || fail "Docker network ${STACK_NETWORK} not found."
[ -n "${GRAFANA_PASSWORD}" ] || fail "Unable to resolve Grafana admin password from .env or /vault/runtime/grafana-admin-password."

echo '== running services =='
docker compose ps

echo
echo '== readiness =='
curl -fsS --max-time 10 "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" >/dev/null
echo "prometheus: ready"

curl -fsS --max-time 10 "http://127.0.0.1:${GRAFANA_PORT}/api/health" \
  | jq -e '.database == "ok"' >/dev/null
echo "grafana: healthy"

docker_net_curl -fsS http://loki:3100/loki/api/v1/labels \
  | jq -e '.status == "success"' >/dev/null
echo "loki: query api reachable"

prometheus_targets_healthy() {
  curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets" \
    | jq -e '
      .data.activeTargets as $targets
      | ($targets | length) > 0
      and ([ $targets[] | select(.labels.job == "broker-actuator" and .health == "up") ] | length) > 0
      and ([ $targets[] | select(.labels.job == "backoffice-actuator" and .health == "up") ] | length) > 0
      and ([ $targets[] | select(.labels.job == "blackbox-http" and .health == "up") ] | length) >= 3
      and ([ $targets[] | select(.labels.job == "blackbox-tcp" and .health == "up") ] | length) >= 3
    ' >/dev/null
}

echo
echo '== prometheus targets =='
retry_until 6 5 "prometheus targets" prometheus_targets_healthy
echo "prometheus targets: broker, backoffice, and blackbox probes are up"

grafana_dashboards_present() {
  curl -fsS -u "${GRAFANA_USER}:${GRAFANA_PASSWORD}" \
    "http://127.0.0.1:${GRAFANA_PORT}/api/search?query=Video%20Chat" \
    | jq -e '
      [ .[] | .uid ] as $uids
      | ($uids | index("videochat-stack-overview")) != null
      and ($uids | index("videochat-service-logs")) != null
      and ($uids | index("videochat-application-signals")) != null
    ' >/dev/null
}

echo
echo '== grafana dashboards =='
retry_until 6 3 "grafana dashboards" grafana_dashboards_present
echo "grafana dashboards: present"

echo
echo '== loki labels =='
docker_net_curl -fsS http://loki:3100/loki/api/v1/label/compose_project/values \
  | jq -e '.data | index("video-chat-docker-stack") != null' >/dev/null
docker_net_curl -fsS http://loki:3100/loki/api/v1/label/compose_service/values \
  | jq -e '.data | index("backoffice") != null and index("broker") != null and index("frontend") != null' >/dev/null
echo "loki labels: compose project and services detected"

custom_metrics_present() {
  curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query?query=videochat_broker_active_rooms" \
    | jq -e '.data.result | length > 0' >/dev/null
  curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query?query=videochat_backoffice_active_rooms" \
    | jq -e '.data.result | length > 0' >/dev/null
}

echo
echo '== custom metrics =='
retry_until 6 5 "custom broker/backoffice metrics" custom_metrics_present
echo "broker/backoffice observability metrics: present"

echo
echo '== generate fresh backoffice access log =='
docker compose exec -T backoffice curl -fsS http://127.0.0.1:7901/api/rooms >/dev/null
echo "generated GET /api/rooms"

prometheus_rooms_metric_present() {
  curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query?query=http_server_requests_seconds_count%7Bjob%3D%22backoffice-actuator%22%2Curi%3D%22%2Fapi%2Frooms%22%7D" \
    | jq -e '.data.result | length > 0' >/dev/null
}

loki_rooms_log_present() {
  local start_ns
  local end_ns
  start_ns="$(python3 - <<'PY'
import time
print(int((time.time() - 600) * 1e9))
PY
)"
  end_ns="$(python3 - <<'PY'
import time
print(int(time.time() * 1e9))
PY
)"

  docker_net_curl -fsSG "http://loki:3100/loki/api/v1/query_range" \
    --data-urlencode 'query={compose_project="video-chat-docker-stack",compose_service="backoffice"} |= "REST GET /api/rooms"' \
    --data-urlencode 'limit=1' \
    --data-urlencode "start=${start_ns}" \
    --data-urlencode "end=${end_ns}" \
    | jq -e '.data.result | length > 0' >/dev/null
}

retry_until 8 5 "prometheus /api/rooms metric" prometheus_rooms_metric_present
echo "prometheus: /api/rooms request metric visible"

retry_until 8 2 "loki /api/rooms access log" loki_rooms_log_present
echo "loki: /api/rooms access log visible"

echo
echo '== promtail delivery =='
docker_net_curl -fsS http://promtail:9080/metrics \
  | awk '
      BEGIN { sent = -1; ok = 0 }
      /^promtail_sent_entries_total\{host="loki:3100"\}/ { sent = $2 }
      /^promtail_request_duration_seconds_count\{host="loki:3100",status_code="204"\}/ { if ($2 > 0) ok = 1 }
      END { exit !(sent > 0 && ok == 1) }
    '
echo "promtail: entries sent to loki"

echo
echo "Monitoring smoke test passed for http://127.0.0.1:${PROMETHEUS_PORT} and http://127.0.0.1:${GRAFANA_PORT}"

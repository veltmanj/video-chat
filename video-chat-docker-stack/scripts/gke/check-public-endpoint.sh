#!/usr/bin/env bash
set -euo pipefail

# Check or wait for the public HTTPS endpoint to become reachable end to end.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command curl
require_command gcloud
require_command python3

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/check-public-endpoint.sh [options]

Check the public GKE endpoint end to end and optionally wait until HTTPS is
actually reachable for end users.

What it checks:
- the managed certificate status in Google Cloud
- the currently resolved public IPv4 addresses for K8S_HOST
- whether DNS matches the reserved static IP
- HTTP reachability and redirect behavior
- HTTPS reachability

Options:
  --env <path>         Use a specific env file instead of k8s/k8s.env
  --wait               Poll until HTTPS returns a successful response
  --timeout <seconds>  Maximum wait time when --wait is used (default: 1800)
  --interval <seconds> Poll interval when --wait is used (default: 20)
  --verbose            Show narrated step-by-step output (default)
  --quiet              Reduce output to the final status block
  --help               Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
WAIT_FOR_HTTPS=false
TIMEOUT_SEC=1800
INTERVAL_SEC=20

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --wait)
      WAIT_FOR_HTTPS=true
      shift
      ;;
    --timeout)
      [[ $# -ge 2 ]] || fail "--timeout requires a value"
      TIMEOUT_SEC="$2"
      shift 2
      ;;
    --interval)
      [[ $# -ge 2 ]] || fail "--interval requires a value"
      INTERVAL_SEC="$2"
      shift 2
      ;;
    --verbose)
      set_log_level verbose
      shift
      ;;
    --quiet)
      set_log_level quiet
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

load_k8s_env "${ENV_FILE}"

: "${GCP_PROJECT_ID:?GCP_PROJECT_ID is required in ${ENV_FILE}}"
: "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME:?K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME is required in ${ENV_FILE}}"
: "${K8S_GKE_GATEWAY_ADDRESS_NAME:?K8S_GKE_GATEWAY_ADDRESS_NAME is required in ${ENV_FILE}}"

fetch_static_ip() {
  gcloud compute addresses describe \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format='value(address)' 2>/dev/null | tr -d '\r'
}

fetch_certificate_state() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format=json
}

resolve_ipv4_addresses() {
  python3 - "${K8S_HOST}" <<'PY'
import socket
import sys

host = sys.argv[1]
addresses = sorted({
    info[4][0]
    for info in socket.getaddrinfo(host, 443, family=socket.AF_INET, type=socket.SOCK_STREAM)
})
for address in addresses:
    print(address)
PY
}

http_status_code() {
  curl -sS -o /dev/null -D /dev/null -w '%{http_code}' --max-time 15 "http://${K8S_HOST}/" 2>/dev/null || true
}

http_location() {
  curl -sS -o /dev/null -D - -w '' --max-time 15 "http://${K8S_HOST}/" 2>/dev/null \
    | awk 'BEGIN{IGNORECASE=1} /^Location:/ { sub(/\r$/, "", $2); print $2; exit }'
}

https_status_code() {
  curl -sS -o /dev/null -D /dev/null -w '%{http_code}' --max-time 15 "https://${K8S_HOST}/" 2>/dev/null || true
}

refresh_state() {
  local cert_json cert_output resolved_output line

  cert_json="$(fetch_certificate_state)"
  cert_output="$(
    CERT_JSON="${cert_json}" python3 - "${K8S_HOST}" <<'PY'
import json
import os
import sys

data = json.loads(os.environ["CERT_JSON"])
managed = data.get("managed", {})
host = sys.argv[1]

print(managed.get("status", ""))
print(managed.get("domainStatus", {}).get(host, ""))
PY
  )"

  CERT_STATUS="$(printf '%s\n' "${cert_output}" | sed -n '1p')"
  DOMAIN_STATUS="$(printf '%s\n' "${cert_output}" | sed -n '2p')"
  CERT_STATUS="${CERT_STATUS:-UNKNOWN}"
  DOMAIN_STATUS="${DOMAIN_STATUS:-UNKNOWN}"

  STATIC_IP="$(fetch_static_ip)"
  RESOLVED_IPV4S=()
  resolved_output="$(resolve_ipv4_addresses 2>/dev/null || true)"
  if [[ -n "${resolved_output}" ]]; then
    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      RESOLVED_IPV4S+=("${line}")
    done <<< "${resolved_output}"
  fi

  HTTP_STATUS="$(http_status_code)"
  HTTP_LOCATION="$(http_location)"
  HTTPS_STATUS="$(https_status_code)"
}

resolved_ipv4_summary() {
  local joined=""
  local ip

  if [[ "${#RESOLVED_IPV4S[@]}" -eq 0 ]]; then
    printf '<none>\n'
    return
  fi

  for ip in "${RESOLVED_IPV4S[@]}"; do
    if [[ -n "${joined}" ]]; then
      joined+=", "
    fi
    joined+="${ip}"
  done
  printf '%s\n' "${joined}"
}

dns_matches_static_ip() {
  local ip
  [[ -n "${STATIC_IP}" ]] || return 1

  for ip in "${RESOLVED_IPV4S[@]}"; do
    [[ "${ip}" == "${STATIC_IP}" ]] && return 0
  done

  return 1
}

https_is_ready() {
  [[ "${HTTPS_STATUS}" =~ ^[23][0-9][0-9]$ ]]
}

print_summary() {
  cat <<EOF
Host: ${K8S_HOST}
Project: ${GCP_PROJECT_ID}
Certificate: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}
Managed status: ${CERT_STATUS}
Domain status: ${DOMAIN_STATUS}
Static IP: ${STATIC_IP:-<unknown>}
Resolved IPv4: $(resolved_ipv4_summary)
DNS matches static IP: $(dns_matches_static_ip && printf 'yes' || printf 'no')
HTTP status: ${HTTP_STATUS:-<none>}
HTTP redirect: ${HTTP_LOCATION:-<none>}
HTTPS status: ${HTTPS_STATUS:-<none>}
HTTPS ready: $(https_is_ready && printf 'yes' || printf 'no')
EOF
}

log_step "Loading public endpoint configuration"
log_info "Env file: ${ENV_FILE}"
log_info "Host: ${K8S_HOST}"
log_info "Certificate: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}"

log_step "Checking public endpoint state"
refresh_state
print_summary

if [[ "${WAIT_FOR_HTTPS}" != "true" ]]; then
  exit 0
fi

if https_is_ready; then
  exit 0
fi

log_step "Waiting for public HTTPS reachability"
deadline=$((SECONDS + TIMEOUT_SEC))

while (( SECONDS < deadline )); do
  sleep "${INTERVAL_SEC}"
  refresh_state
  print_summary

  if https_is_ready; then
    exit 0
  fi
done

fail "Public HTTPS for ${K8S_HOST} is still not reachable after ${TIMEOUT_SEC}s."

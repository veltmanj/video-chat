#!/usr/bin/env bash
set -euo pipefail

# Inspect or wait for the Google-managed SSL certificate used by the GKE Gateway.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
K8S_SCRIPT_DIR="${ROOT_DIR}/scripts/k8s"
# shellcheck disable=SC1091
source "${K8S_SCRIPT_DIR}/common.sh"

require_command gcloud
require_command python3

usage() {
  cat <<'EOF'
Usage: ./scripts/gke/check-certificate.sh [options]

Show the current Google-managed SSL certificate state for the configured GKE
Gateway host and optionally wait until it becomes ACTIVE.

What it checks:
- the managed certificate status in Google Cloud
- the per-domain status for K8S_HOST
- the reserved global static IP
- the currently resolved public IPv4 addresses for K8S_HOST

Options:
  --env <path>         Use a specific env file instead of k8s/k8s.env
  --wait               Poll until the certificate becomes ACTIVE
  --timeout <seconds>  Maximum wait time when --wait is used (default: 1800)
  --interval <seconds> Poll interval when --wait is used (default: 30)
  --verbose            Show narrated step-by-step output (default)
  --quiet              Reduce output to the final status block
  --help               Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"
WAIT_FOR_ACTIVE=false
TIMEOUT_SEC=1800
INTERVAL_SEC=30

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --wait)
      WAIT_FOR_ACTIVE=true
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

certificate_exists() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" >/dev/null 2>&1
}

describe_certificate_json() {
  gcloud compute ssl-certificates describe \
    "${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format=json
}

fetch_static_ip() {
  gcloud compute addresses describe \
    "${K8S_GKE_GATEWAY_ADDRESS_NAME}" \
    --global \
    --project "${GCP_PROJECT_ID}" \
    --format='value(address)' 2>/dev/null | tr -d '\r'
}

resolve_ipv4_addresses() {
  python3 - "$K8S_HOST" <<'PY'
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

refresh_state() {
  local cert_json cert_state_output resolved_output line

  cert_json="$(describe_certificate_json)"
  cert_state_output="$(
    CERT_JSON="${cert_json}" python3 - "${K8S_HOST}" <<'PY'
import json
import os
import sys

data = json.loads(os.environ["CERT_JSON"])
managed = data.get("managed", {})
host = sys.argv[1]

print(managed.get("status", ""))
print(managed.get("domainStatus", {}).get(host, ""))
print(data.get("creationTimestamp", ""))
PY
  )"

  CERT_STATUS="$(printf '%s\n' "${cert_state_output}" | sed -n '1p')"
  DOMAIN_STATUS="$(printf '%s\n' "${cert_state_output}" | sed -n '2p')"
  CERT_CREATED_AT="$(printf '%s\n' "${cert_state_output}" | sed -n '3p')"

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
}

certificate_is_active() {
  [[ "${CERT_STATUS}" == "ACTIVE" && "${DOMAIN_STATUS}" == "ACTIVE" ]]
}

resolved_ipv4_summary() {
  if [[ "${#RESOLVED_IPV4S[@]}" -eq 0 ]]; then
    printf '<none>\n'
    return
  fi

  local joined=""
  local ip
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

print_summary() {
  cat <<EOF
Certificate: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}
Host: ${K8S_HOST}
Project: ${GCP_PROJECT_ID}
Managed status: ${CERT_STATUS}
Domain status: ${DOMAIN_STATUS}
Static IP: ${STATIC_IP:-<unknown>}
Resolved IPv4: $(resolved_ipv4_summary)
DNS matches static IP: $(dns_matches_static_ip && printf 'yes' || printf 'no')
Created: ${CERT_CREATED_AT:-<unknown>}
Completed: $(certificate_is_active && printf 'yes' || printf 'no')
EOF
}

log_step "Loading certificate status configuration"
log_info "Env file: ${ENV_FILE}"
log_info "Project: ${GCP_PROJECT_ID}"
log_info "Host: ${K8S_HOST}"
log_info "Certificate: ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME}"

certificate_exists || fail "Managed certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME} does not exist in project ${GCP_PROJECT_ID}."

log_step "Checking managed certificate status"
refresh_state
print_summary

if [[ "${WAIT_FOR_ACTIVE}" != "true" ]]; then
  exit 0
fi

if certificate_is_active; then
  exit 0
fi

log_step "Waiting for managed certificate to become ACTIVE"
deadline=$((SECONDS + TIMEOUT_SEC))

while (( SECONDS < deadline )); do
  sleep "${INTERVAL_SEC}"
  refresh_state
  print_summary

  if certificate_is_active; then
    exit 0
  fi
done

fail "Managed certificate ${K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME} is still not ACTIVE after ${TIMEOUT_SEC}s."

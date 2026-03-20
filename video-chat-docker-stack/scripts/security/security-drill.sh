#!/usr/bin/env bash
set -euo pipefail

# Runs live negative-auth probes against the broker and auto-mitigates obvious exposure if one of the
# probes succeeds unexpectedly.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
BROKER_DIR="$(cd -- "${ROOT_DIR}/../video-chat-rsocket-broker" && pwd)"
NETWORK_NAME="videochat-network"
MAVEN_IMAGE="maven:3.9.9-eclipse-temurin-21"
HOST_VALUE=""
HTTPS_RESOLVE=()
HTTP_RESOLVE=()

cd "${ROOT_DIR}"

usage() {
  cat <<'EOF'
Usage: ./scripts/security-drill.sh

Run the live security drill against the local stack. The script probes the
frontend, backoffice, and broker for obvious exposure, applies automatic
mitigations in .env if needed, rebuilds edge services, and retries the probes.

Arguments:
  -h, --help    Show this help text.
EOF
}

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

set_env_value() {
  local key="$1"
  local value="$2"

  if grep -q -E "^${key}=" .env 2>/dev/null; then
    perl -0pi -e "s/^${key}=.*\$/${key}=${value}/m" .env
  else
    printf '%s=%s\n' "${key}" "${value}" >> .env
  fi
}

refresh_host_config() {
  HOST_VALUE="$(read_env_value "VIDEOCHAT_HOST")"
  HOST_VALUE="${HOST_VALUE:-localhost}"
  HTTPS_RESOLVE=(--resolve "${HOST_VALUE}:443:127.0.0.1")
  HTTP_RESOLVE=(--resolve "${HOST_VALUE}:80:127.0.0.1")
}

ensure_stack_is_running() {
  docker compose up -d --build frontend social-db backoffice caddy vault vault-init broker
  docker compose ps
}

wait_for_https_edge() {
  local attempt
  for attempt in $(seq 1 30); do
    if curl -sk --http1.1 --max-time 5 "${HTTPS_RESOLVE[@]}" "https://${HOST_VALUE}/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  return 1
}

cleanup_attack_secret() {
  local vault_token vault_mount attack_path
  vault_token="$(read_env_value "VAULT_DEV_ROOT_TOKEN_ID")"
  vault_mount="$(read_env_value "VAULT_KV_MOUNT")"
  attack_path="$(read_env_value "VAULT_X_SECRET_PATH")"

  vault_token="${vault_token:-videochat-dev-root-token}"
  vault_mount="${vault_mount:-secret}"
  attack_path="${attack_path:-jwt/providers/x}"

  docker compose exec -T vault sh -lc \
    "export VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN='${vault_token}'; vault kv delete '${vault_mount}/${attack_path}' >/dev/null 2>&1 || true"
}

run_probe() {
  local vault_token vault_mount attack_path attack_enabled
  vault_token="$(read_env_value "VAULT_DEV_ROOT_TOKEN_ID")"
  vault_mount="$(read_env_value "VAULT_KV_MOUNT")"
  attack_path="$(read_env_value "VAULT_X_SECRET_PATH")"
  attack_enabled="$(read_env_value "BROKER_JWT_X_ENABLED")"

  vault_token="${vault_token:-videochat-dev-root-token}"
  vault_mount="${vault_mount:-secret}"
  attack_path="${attack_path:-jwt/providers/x}"
  attack_enabled="${attack_enabled:-false}"

  if [[ "${attack_enabled}" != "true" ]]; then
    attack_path=""
  fi

  docker run --rm \
    --network "${NETWORK_NAME}" \
    -v "${BROKER_DIR}:/app" \
    -v "${HOME}/.m2:/root/.m2" \
    -w /app \
    "${MAVEN_IMAGE}" \
    mvn -q \
      -DliveStackSecurityProbe=true \
      -Dtest=LiveStackSecurityProbeTest \
      -DsecurityProbeBrokerUri=ws://broker:9898/rsocket \
      -DsecurityProbeVaultUri=http://vault:8200 \
      -DsecurityProbeVaultToken="${vault_token}" \
      -DsecurityProbeAttackVaultMount="${vault_mount}" \
      -DsecurityProbeAttackVaultPath="${attack_path}" \
      test
}

run_frontend_probes() {
  local header_file body_file runtime_file status
  header_file="$(mktemp)"
  body_file="$(mktemp)"
  runtime_file="$(mktemp)"

  status="$(curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" \
    -D "${header_file}" \
    -o "${body_file}" \
    -w "%{http_code}" \
    "https://${HOST_VALUE}/")"

  [[ "${status}" == "200" ]] || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: expected 200 from /, got ${status}" >&2
    return 1
  }

  grep -qi "^x-content-type-options: nosniff" "${header_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: missing X-Content-Type-Options nosniff" >&2
    return 1
  }

  grep -qi "^x-frame-options: DENY" "${header_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: missing X-Frame-Options DENY" >&2
    return 1
  }

  grep -qi "^content-security-policy: .*frame-ancestors 'none'" "${header_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: CSP is missing frame-ancestors 'none'" >&2
    return 1
  }

  grep -qi "^content-security-policy: .*script-src 'self'" "${header_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: CSP is missing script-src 'self'" >&2
    return 1
  }

  grep -qi "^permissions-policy: camera=(self), microphone=(), geolocation=()" "${header_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: missing expected Permissions-Policy" >&2
    return 1
  }

  status="$(curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" \
    -o "${runtime_file}" \
    -w "%{http_code}" \
    "https://${HOST_VALUE}/assets/runtime-config.js")"

  [[ "${status}" == "200" ]] || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: runtime-config.js returned ${status}" >&2
    return 1
  }

  grep -q "googleClientId:" "${runtime_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: runtime-config.js is missing googleClientId" >&2
    return 1
  }

  grep -q "appMode:" "${runtime_file}" || {
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: runtime-config.js is missing appMode" >&2
    return 1
  }

  if rg -q "VAULT_|BROKER_JWT_|BROKER_BACKOFFICE|videochat-dev-root-token|X-Vault-Token" "${runtime_file}"; then
    rm -f "${header_file}" "${body_file}" "${runtime_file}"
    echo "Frontend probe failed: runtime-config.js appears to leak stack secrets" >&2
    return 1
  fi

  rm -f "${header_file}" "${body_file}" "${runtime_file}"
}

run_backoffice_probes() {
  local status

  docker compose exec -T backoffice curl -fsS http://127.0.0.1:7901/actuator/health >/dev/null

  status="$(curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" -o /dev/null -w "%{http_code}" \
    "https://${HOST_VALUE}/social-api/social/v1/me")"
  [[ "${status}" == "401" || "${status}" == "403" ]] || {
    echo "Backoffice probe failed: anonymous /social-api/social/v1/me returned ${status}" >&2
    return 1
  }

  if [[ "$(read_env_value "BACKOFFICE_API_EXPOSED")" != "true" ]]; then
    status="$(curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" -o /dev/null -w "%{http_code}" \
      "https://${HOST_VALUE}/backoffice-api/api/rooms")"
    [[ "${status}" != "200" ]] || {
      echo "Backoffice probe failed: public /backoffice-api/api/rooms is reachable" >&2
      return 1
    }
  fi

  if [[ "$(read_env_value "BACKOFFICE_RSOCKET_EXPOSED")" != "true" ]]; then
    status="$(curl -sk --http1.1 --max-time 10 "${HTTPS_RESOLVE[@]}" \
      -o /dev/null \
      -w "%{http_code}" \
      -H 'Connection: Upgrade' \
      -H 'Upgrade: websocket' \
      -H 'Sec-WebSocket-Version: 13' \
      -H 'Sec-WebSocket-Key: c2VjdXJpdHktZHJpbGw=' \
      -H 'Sec-WebSocket-Protocol: rsocket' \
      "https://${HOST_VALUE}/backoffice-rsocket")"
    [[ "${status}" != "101" ]] || {
      echo "Backoffice probe failed: public /backoffice-rsocket accepted websocket upgrade" >&2
      return 1
    }
  fi
}

run_edge_probes() {
  run_frontend_probes
  run_backoffice_probes
}

apply_mitigations() {
  local changed=0
  local google_client_id google_audience

  if [[ "$(read_env_value "BROKER_JWT_ENABLED")" != "true" ]]; then
    set_env_value "BROKER_JWT_ENABLED" "true"
    changed=1
  fi

  if [[ -z "$(read_env_value "BROKER_JWT_CLOCK_SKEW")" ]]; then
    set_env_value "BROKER_JWT_CLOCK_SKEW" "30s"
    changed=1
  fi

  if [[ "$(read_env_value "BROKER_JWT_X_ENABLED")" != "false" ]]; then
    set_env_value "BROKER_JWT_X_ENABLED" "false"
    changed=1
  fi

  if [[ "$(read_env_value "BACKOFFICE_API_EXPOSED")" != "false" ]]; then
    set_env_value "BACKOFFICE_API_EXPOSED" "false"
    changed=1
  fi

  if [[ "$(read_env_value "BACKOFFICE_RSOCKET_EXPOSED")" != "false" ]]; then
    set_env_value "BACKOFFICE_RSOCKET_EXPOSED" "false"
    changed=1
  fi

  google_client_id="$(read_env_value "GOOGLE_OAUTH_CLIENT_ID")"
  google_audience="$(read_env_value "BROKER_JWT_GOOGLE_AUDIENCE")"
  if [[ -n "${google_client_id}" && -z "${google_audience}" ]]; then
    set_env_value "BROKER_JWT_GOOGLE_AUDIENCE" "${google_client_id}"
    changed=1
  fi

  cleanup_attack_secret

  echo
  if (( changed )); then
    echo "Applied stack mitigations in .env and rebuilding edge services."
  else
    echo "No .env changes were needed; rebuilding current images to pick up security fixes and flush cached state."
  fi
  refresh_host_config
  docker compose up -d --build frontend broker caddy
}

contain_stack() {
  cleanup_attack_secret
  echo
  echo "Probe still succeeded after mitigation. Stopping broker and Caddy to contain exposure."
  docker compose stop broker caddy
}

main() {
  refresh_host_config
  echo "== ensuring broker dependencies are up =="
  ensure_stack_is_running

  wait_for_https_edge

  echo
  echo "== running live security drill =="
  if run_edge_probes && run_probe; then
    cleanup_attack_secret
    echo
    echo "Security drill passed: frontend, backoffice, and broker rejected the malicious probes."
    exit 0
  fi

  echo
  echo "Security drill failed: at least one malicious probe was accepted. Applying mitigations."
  apply_mitigations
  wait_for_https_edge

  echo
  echo "== re-running live security drill after mitigation =="
  if run_edge_probes && run_probe; then
    cleanup_attack_secret
    echo
    echo "Mitigation succeeded: the stack now rejects the frontend, backoffice, and broker probes."
    exit 0
  fi

  contain_stack
  exit 1
}

main "$@"

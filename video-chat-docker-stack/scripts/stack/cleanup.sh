#!/usr/bin/env bash
set -euo pipefail

# Tears down the local stack, removes all Docker-managed state, optionally rotates local bootstrap secrets,
# and can rebuild the stack from a clean slate.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"

ROTATE_SECRETS=false
REBUILD=false
KEEP_LOCAL_CA=false

usage() {
  cat <<'EOF'
Usage: ./scripts/cleanup.sh [--rotate-secrets] [--rebuild] [--keep-local-ca]

Options:
  --rotate-secrets  Rotate local Vault bootstrap secrets in .env before rebuild.
  --rebuild         Bring the stack back with docker compose up -d --build after cleanup.
  --keep-local-ca   Keep the exported local Caddy CA file instead of deleting it.
  -h, --help        Show this help text.

The script removes all compose-managed volumes with `docker compose down -v --remove-orphans`,
which resets Vault runtime state, databases, Grafana, Prometheus, Loki, MinIO, and Caddy state.
EOF
}

fail() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

random_alnum() {
  local length="$1"
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${length}"
}

read_env_value() {
  local key="$1"
  local value
  value="$(grep -E "^${key}=" "${ENV_FILE}" 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
  printf '%s' "${value}"
}

update_env_value() {
  local key="$1"
  local value="$2"
  local tmp_file
  tmp_file="$(mktemp)"

  awk -v key="${key}" -v value="${value}" '
    BEGIN { updated = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      updated = 1
      next
    }
    { print }
    END {
      if (updated == 0) {
        print key "=" value
      }
    }
  ' "${ENV_FILE}" >"${tmp_file}"

  mv "${tmp_file}" "${ENV_FILE}"
}

rotate_local_secrets() {
  [ -f "${ENV_FILE}" ] || fail "Missing ${ENV_FILE}"

  local vault_token
  local db_password
  local minio_password
  local grafana_password

  vault_token="videochat-dev-root-$(random_alnum 24)"
  db_password="$(random_alnum 32)"
  minio_password="$(random_alnum 32)"
  grafana_password="$(random_alnum 32)"

  update_env_value "VAULT_DEV_ROOT_TOKEN_ID" "${vault_token}"
  update_env_value "VAULT_BOOTSTRAP_SOCIAL_DB_PASSWORD" "${db_password}"
  update_env_value "VAULT_BOOTSTRAP_MINIO_ROOT_PASSWORD" "${minio_password}"
  update_env_value "VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD" "${grafana_password}"

  echo "Rotated local Vault bootstrap secrets in ${ENV_FILE}"
}

remove_local_ca() {
  local ca_filename
  ca_filename="$(read_env_value "CADDY_LOCAL_CA_FILENAME")"
  ca_filename="${ca_filename:-caddy-local-root-ca.crt}"

  if [ -e "${ROOT_DIR}/${ca_filename}" ]; then
    rm -rf "${ROOT_DIR:?}/${ca_filename}"
    echo "Removed exported local CA file ${ca_filename}"
  fi
}

prepare_local_ca_placeholder() {
  local ca_filename
  ca_filename="$(read_env_value "CADDY_LOCAL_CA_FILENAME")"
  ca_filename="${ca_filename:-caddy-local-root-ca.crt}"

  rm -rf "${ROOT_DIR:?}/${ca_filename}"
  : > "${ROOT_DIR}/${ca_filename}"
}

while (($# > 0)); do
  case "$1" in
    --rotate-secrets)
      ROTATE_SECRETS=true
      ;;
    --rebuild)
      REBUILD=true
      ;;
    --keep-local-ca)
      KEEP_LOCAL_CA=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "Unknown option: $1"
      ;;
  esac
  shift
done

require_command docker
require_command awk

cd "${ROOT_DIR}"

if [ "${ROTATE_SECRETS}" = "true" ]; then
  rotate_local_secrets
fi

echo "Stopping stack and removing compose-managed state..."
docker compose down -v --remove-orphans

if [ "${KEEP_LOCAL_CA}" != "true" ]; then
  remove_local_ca
fi

if [ "${REBUILD}" = "true" ]; then
  if [ "${KEEP_LOCAL_CA}" != "true" ]; then
    prepare_local_ca_placeholder
  fi
  echo "Rebuilding stack from a clean slate..."
  docker compose up -d --build
  if [ "${KEEP_LOCAL_CA}" != "true" ]; then
    echo "Exporting fresh local Caddy CA..."
    "${ROOT_DIR}/scripts/export-caddy-root-ca.sh"
  fi
  echo "Stack rebuilt. Run ./scripts/check.sh or ./scripts/monitoring-smoke.sh to verify."
else
  echo "Cleanup complete."
fi

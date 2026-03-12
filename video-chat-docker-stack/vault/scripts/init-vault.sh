#!/bin/sh
set -eu

# Bootstraps the local Vault dev server for the rest of the compose stack.
# The flow is:
# 1. Ensure the KV-v2 mount and the shared runtime-volume directory exist.
# 2. Resolve runtime secrets from env/files/persisted runtime state or generate them.
# 3. Write those secrets into Vault as the canonical source of truth.
# 4. Render the current secret values back out into /vault/runtime for containers that only know
#    how to read env vars or *_FILE inputs.
# 5. Load provider JWKS documents into Vault and mint a scoped broker token that can read only
#    those JWT provider paths.

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN is required}"
VAULT_KV_MOUNT="${VAULT_KV_MOUNT:-secret}"
VAULT_PROVIDER_FIELD="${VAULT_PROVIDER_FIELD:-jwks_json}"
VAULT_RUNTIME_DIR="${VAULT_RUNTIME_DIR:-/vault/runtime}"
VAULT_SOCIAL_DB_SECRET_PATH="${VAULT_SOCIAL_DB_SECRET_PATH:-runtime/social-db}"
VAULT_MINIO_SECRET_PATH="${VAULT_MINIO_SECRET_PATH:-runtime/minio}"
VAULT_GRAFANA_SECRET_PATH="${VAULT_GRAFANA_SECRET_PATH:-runtime/grafana}"
VAULT_BROKER_POLICY_NAME="${VAULT_BROKER_POLICY_NAME:-broker-jwt-read}"

export VAULT_ADDR
export VAULT_TOKEN

ensure_runtime_dir() {
  # This shared volume is the bridge between the one-shot bootstrap container and the long-running
  # services. Files here persist across container recreation, which lets later boots reuse the same
  # generated secrets instead of silently rotating them.
  mkdir -p "${VAULT_RUNTIME_DIR}"
  chmod 755 "${VAULT_RUNTIME_DIR}"
}

ensure_kv_mount() {
  if vault secrets list -format=json | grep -q "\"${VAULT_KV_MOUNT}/\""; then
    echo "Vault mount ${VAULT_KV_MOUNT}/ already enabled"
    return 0
  fi

  vault secrets enable -path="${VAULT_KV_MOUNT}" kv-v2 >/dev/null
  echo "Enabled Vault kv-v2 mount ${VAULT_KV_MOUNT}/"
}

generate_secret() {
  length="${1:-32}"
  tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${length}"
}

resolve_secret() {
  env_value="$1"
  bootstrap_file="$2"
  runtime_file="$3"
  length="$4"
  label="$5"

  # Precedence matters because these secrets may already exist in several places:
  # explicit env override -> checked-in/locally managed bootstrap file -> previously rendered runtime
  # file -> generated fallback. The runtime-file step keeps generated local passwords stable across
  # rebuilds even though the Vault dev server itself is ephemeral.
  if [ -n "${env_value}" ]; then
    echo "Using ${label} from environment" >&2
    printf '%s' "${env_value}"
    return 0
  fi

  if [ -s "${bootstrap_file}" ]; then
    echo "Using ${label} from ${bootstrap_file}" >&2
    cat "${bootstrap_file}"
    return 0
  fi

  if [ -s "${runtime_file}" ]; then
    echo "Using ${label} from persisted runtime file ${runtime_file}" >&2
    cat "${runtime_file}"
    return 0
  fi

  echo "Generating ${label}" >&2
  generate_secret "${length}"
}

store_runtime_secret() {
  secret_path="$1"
  shift

  # Vault remains the canonical store. Runtime files are just a projection for containers that do
  # not talk to Vault themselves.
  vault kv put -mount="${VAULT_KV_MOUNT}" "${secret_path}" "$@" >/dev/null
  echo "Stored ${secret_path} in ${VAULT_KV_MOUNT}/${secret_path}"
}

render_runtime_file() {
  secret_path="$1"
  secret_field="$2"
  runtime_file="$3"

  # Render atomically so services never observe a partially written secret file.
  temp_file="$(mktemp)"
  vault kv get -mount="${VAULT_KV_MOUNT}" -field="${secret_field}" "${secret_path}" >"${temp_file}"
  chmod 644 "${temp_file}"
  mv "${temp_file}" "${runtime_file}"
  echo "Rendered ${secret_path}.${secret_field} to ${runtime_file}"
}

write_broker_policy() {
  policy_file="$(mktemp)"

  # The broker should not inherit the Vault root/dev token. Instead it gets a least-privilege
  # policy that can read only the configured provider JWKS documents.
  cat >"${policy_file}" <<EOF
path "${VAULT_KV_MOUNT}/data/${VAULT_GOOGLE_SECRET_PATH:-jwt/providers/google}" {
  capabilities = ["read"]
}

path "${VAULT_KV_MOUNT}/data/${VAULT_APPLE_SECRET_PATH:-jwt/providers/apple}" {
  capabilities = ["read"]
}

path "${VAULT_KV_MOUNT}/data/${VAULT_X_SECRET_PATH:-jwt/providers/x}" {
  capabilities = ["read"]
}
EOF

  vault policy write "${VAULT_BROKER_POLICY_NAME}" "${policy_file}" >/dev/null
  rm -f "${policy_file}"
  echo "Updated Vault policy ${VAULT_BROKER_POLICY_NAME}"
}

render_broker_token() {
  runtime_file="${VAULT_RUNTIME_DIR}/broker-jwt-vault-token"
  temp_file="$(mktemp)"

  # The broker launcher reads this file and exports BROKER_JWT_VAULT_TOKEN just before the Java
  # process starts.
  vault token create -orphan -policy="${VAULT_BROKER_POLICY_NAME}" -field=token >"${temp_file}"
  chmod 644 "${temp_file}"
  mv "${temp_file}" "${runtime_file}"
  echo "Minted scoped broker Vault token at ${runtime_file}"
}

bootstrap_runtime_secrets() {
  # Resolve each secret once, write it into Vault, then render the concrete file consumed by the
  # runtime services. This keeps service startup simple while still centralizing the secret values in
  # Vault.
  social_db_password="$(resolve_secret \
    "${VAULT_BOOTSTRAP_SOCIAL_DB_PASSWORD:-${SOCIAL_DB_PASSWORD:-}}" \
    "/vault/secrets/social-db-password" \
    "${VAULT_RUNTIME_DIR}/social-db-password" \
    "32" \
    "social database password")"
  minio_root_password="$(resolve_secret \
    "${VAULT_BOOTSTRAP_MINIO_ROOT_PASSWORD:-${MINIO_ROOT_PASSWORD:-}}" \
    "/vault/secrets/minio-root-password" \
    "${VAULT_RUNTIME_DIR}/minio-root-password" \
    "32" \
    "MinIO root password")"
  grafana_admin_password="$(resolve_secret \
    "${VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD:-${GRAFANA_ADMIN_PASSWORD:-}}" \
    "/vault/secrets/grafana-admin-password" \
    "${VAULT_RUNTIME_DIR}/grafana-admin-password" \
    "32" \
    "Grafana admin password")"

  store_runtime_secret \
    "${VAULT_SOCIAL_DB_SECRET_PATH}" \
    username="${SOCIAL_DB_USER:-videochat}" \
    database="${SOCIAL_DB_NAME:-videochat}" \
    password="${social_db_password}"
  store_runtime_secret \
    "${VAULT_MINIO_SECRET_PATH}" \
    access_key="${MINIO_ROOT_USER:-videochat-minio}" \
    secret_key="${minio_root_password}"
  store_runtime_secret \
    "${VAULT_GRAFANA_SECRET_PATH}" \
    admin_user="${GRAFANA_ADMIN_USER:-admin}" \
    admin_password="${grafana_admin_password}"

  render_runtime_file "${VAULT_SOCIAL_DB_SECRET_PATH}" "password" "${VAULT_RUNTIME_DIR}/social-db-password"
  render_runtime_file "${VAULT_MINIO_SECRET_PATH}" "secret_key" "${VAULT_RUNTIME_DIR}/minio-root-password"
  render_runtime_file "${VAULT_GRAFANA_SECRET_PATH}" "admin_password" "${VAULT_RUNTIME_DIR}/grafana-admin-password"
}

load_provider() {
  provider_name="$1"
  source_url="$2"
  secret_path="$3"
  source_file="/vault/secrets/${provider_name}-jwks.json"
  temp_file=""

  # Provider keys can come from a committed/local JSON file or be fetched from the configured JWKS
  # URL. Either way they end up in the same Vault KV path for the broker to read.
  if [ -s "${source_file}" ]; then
    temp_file="${source_file}"
    echo "Loading ${provider_name} JWKS from ${source_file}"
  elif [ -n "${source_url}" ]; then
    temp_file="$(mktemp)"
    echo "Fetching ${provider_name} JWKS from ${source_url}"
    wget -q -O "${temp_file}" "${source_url}"
  else
    echo "Skipping ${provider_name}: no JWKS file or URL configured"
    return 0
  fi

  if [ ! -s "${temp_file}" ]; then
    echo "Skipping ${provider_name}: fetched JWKS is empty"
    [ "${temp_file}" = "${source_file}" ] || rm -f "${temp_file}"
    return 0
  fi

  vault kv put -mount="${VAULT_KV_MOUNT}" "${secret_path}" "${VAULT_PROVIDER_FIELD}=@${temp_file}" >/dev/null
  echo "Stored ${provider_name} JWKS in ${VAULT_KV_MOUNT}/${secret_path}"

  [ "${temp_file}" = "${source_file}" ] || rm -f "${temp_file}"
}

# Keep the execution order explicit: the runtime volume and secret values must exist before any
# service launcher tries to read /vault/runtime/*, and the broker token must be minted after the
# read policy is updated.
ensure_runtime_dir
ensure_kv_mount
bootstrap_runtime_secrets
load_provider "google" "${VAULT_GOOGLE_JWKS_URL:-}" "${VAULT_GOOGLE_SECRET_PATH:-jwt/providers/google}"
load_provider "apple" "${VAULT_APPLE_JWKS_URL:-}" "${VAULT_APPLE_SECRET_PATH:-jwt/providers/apple}"
load_provider "x" "${VAULT_X_JWKS_URL:-}" "${VAULT_X_SECRET_PATH:-jwt/providers/x}"
write_broker_policy
render_broker_token

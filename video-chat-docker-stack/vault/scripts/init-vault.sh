#!/bin/sh
set -eu

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN is required}"
VAULT_KV_MOUNT="${VAULT_KV_MOUNT:-secret}"
VAULT_PROVIDER_FIELD="${VAULT_PROVIDER_FIELD:-jwks_json}"

export VAULT_ADDR
export VAULT_TOKEN

ensure_kv_mount() {
  if vault secrets list -format=json | grep -q "\"${VAULT_KV_MOUNT}/\""; then
    echo "Vault mount ${VAULT_KV_MOUNT}/ already enabled"
    return 0
  fi

  vault secrets enable -path="${VAULT_KV_MOUNT}" kv-v2 >/dev/null
  echo "Enabled Vault kv-v2 mount ${VAULT_KV_MOUNT}/"
}

load_provider() {
  provider_name="$1"
  source_url="$2"
  secret_path="$3"
  source_file="/vault/secrets/${provider_name}-jwks.json"
  temp_file=""

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

ensure_kv_mount
load_provider "google" "${VAULT_GOOGLE_JWKS_URL:-}" "${VAULT_GOOGLE_SECRET_PATH:-jwt/providers/google}"
load_provider "apple" "${VAULT_APPLE_JWKS_URL:-}" "${VAULT_APPLE_SECRET_PATH:-jwt/providers/apple}"
load_provider "x" "${VAULT_X_JWKS_URL:-}" "${VAULT_X_SECRET_PATH:-jwt/providers/x}"

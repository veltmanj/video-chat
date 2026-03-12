#!/bin/sh
set -eu

# The broker is the only runtime service that talks to Vault directly. Even here we do not pass the
# Vault root/dev token through docker-compose; vault-init mints a scoped read-only token, writes it
# to /vault/runtime, and this launcher exports it just before boot.

BROKER_JWT_VAULT_TOKEN_FILE="${BROKER_JWT_VAULT_TOKEN_FILE:-/vault/runtime/broker-jwt-vault-token}"

if [ -z "${BROKER_JWT_VAULT_TOKEN:-}" ] && [ -s "${BROKER_JWT_VAULT_TOKEN_FILE}" ]; then
  BROKER_JWT_VAULT_TOKEN="$(cat "${BROKER_JWT_VAULT_TOKEN_FILE}")"
  export BROKER_JWT_VAULT_TOKEN
fi

if [ -z "${BROKER_JWT_VAULT_TOKEN:-}" ]; then
  echo "Missing required broker Vault token. Checked ${BROKER_JWT_VAULT_TOKEN_FILE}." >&2
  exit 1
fi

exec java -jar /app/app.jar "$@"

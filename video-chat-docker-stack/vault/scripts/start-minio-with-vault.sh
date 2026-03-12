#!/bin/sh
set -eu

# MinIO understands its root password via env vars / *_FILE, not Vault. This launcher keeps MinIO
# unchanged by reading the vault-init runtime file and exporting MINIO_ROOT_PASSWORD on startup.

MINIO_ROOT_PASSWORD_FILE="${MINIO_ROOT_PASSWORD_FILE:-/vault/runtime/minio-root-password}"

if [ -z "${MINIO_ROOT_PASSWORD:-}" ]; then
  if [ ! -s "${MINIO_ROOT_PASSWORD_FILE}" ]; then
    echo "Missing required MinIO root password file: ${MINIO_ROOT_PASSWORD_FILE}" >&2
    exit 1
  fi

  MINIO_ROOT_PASSWORD="$(cat "${MINIO_ROOT_PASSWORD_FILE}")"
  export MINIO_ROOT_PASSWORD
fi

exec /usr/bin/docker-entrypoint.sh "$@"

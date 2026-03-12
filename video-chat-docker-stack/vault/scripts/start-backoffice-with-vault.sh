#!/bin/sh
set -eu

# Backoffice itself does not read from Vault. This launcher translates the runtime files rendered by
# vault-init into the plain env vars Spring Boot expects before starting the JVM.

read_secret_file() {
  secret_file="$1"
  secret_label="$2"

  if [ ! -s "${secret_file}" ]; then
    echo "Missing required secret file for ${secret_label}: ${secret_file}" >&2
    exit 1
  fi

  cat "${secret_file}"
}

SPRING_DATASOURCE_PASSWORD_FILE="${SPRING_DATASOURCE_PASSWORD_FILE:-/vault/runtime/social-db-password}"
BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY_FILE="${BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY_FILE:-/vault/runtime/minio-root-password}"

# Only hydrate the env var when an explicit value was not provided. That keeps manual overrides
# possible while making the Vault-rendered runtime file the normal path.
if [ -z "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  SPRING_DATASOURCE_PASSWORD="$(read_secret_file "${SPRING_DATASOURCE_PASSWORD_FILE}" "Spring datasource password")"
  export SPRING_DATASOURCE_PASSWORD
fi

if [ -z "${BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY:-}" ]; then
  BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY="$(read_secret_file "${BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY_FILE}" "MinIO secret key")"
  export BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY
fi

exec java -jar /app/app.jar "$@"

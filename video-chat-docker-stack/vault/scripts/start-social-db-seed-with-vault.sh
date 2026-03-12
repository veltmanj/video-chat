#!/bin/sh
set -eu

PGPASSWORD_FILE="${PGPASSWORD_FILE:-/vault/runtime/social-db-password}"

if [ -z "${PGPASSWORD:-}" ]; then
  if [ ! -s "${PGPASSWORD_FILE}" ]; then
    echo "Missing required PostgreSQL password file: ${PGPASSWORD_FILE}" >&2
    exit 1
  fi

  PGPASSWORD="$(cat "${PGPASSWORD_FILE}")"
  export PGPASSWORD
fi

exec /seed/scripts/seed-dev-social-db.sh "$@"

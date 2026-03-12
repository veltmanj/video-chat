#!/bin/sh
set -eu

# The seed job uses psql tooling that expects PGPASSWORD. vault-init renders the shared database
# password to /vault/runtime/social-db-password and this wrapper turns that file back into the env
# var before handing off to the actual seed script.

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

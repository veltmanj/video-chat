#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename -- "${ROOT_DIR}")}"

find_running_container() {
  local service_name="${1}"

  docker ps \
    --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" \
    --filter "label=com.docker.compose.service=${service_name}" \
    --format '{{.Names}}' \
    | head -n 1
}

cd "${ROOT_DIR}"

dump_file="${1:-}"
target_db="${2:-}"

if [[ -z "${dump_file}" ]]; then
  echo 'Usage: ./scripts/restore-social-db.sh <dump-file> [target-db-name]' >&2
  exit 1
fi

if [[ ! -f "${dump_file}" ]]; then
  printf 'Dump file not found: %s\n' "${dump_file}" >&2
  exit 1
fi

social_db_container="$(find_running_container social-db)"
if [[ -z "${social_db_container}" ]]; then
  echo 'social-db is not running.' >&2
  exit 1
fi

if [[ -z "${target_db}" ]]; then
  target_db="$(docker exec "${social_db_container}" sh -lc 'printf %s "$POSTGRES_DB"' | tr -d '\r')"
fi

backoffice_container="$(find_running_container backoffice)"

cleanup() {
  if [[ -n "${backoffice_container}" ]]; then
    docker start "${backoffice_container}" >/dev/null
  fi
}

trap cleanup EXIT

if [[ -n "${backoffice_container}" ]]; then
  docker stop "${backoffice_container}" >/dev/null
fi

docker exec -i -e TARGET_DB="${target_db}" "${social_db_container}" sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\''$TARGET_DB'\'' AND pid <> pg_backend_pid();" >/dev/null
  if ! psql -tA -U "$POSTGRES_USER" -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '\''$TARGET_DB'\''" | grep -qx 1; then
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"$TARGET_DB\""
  fi
'

docker exec -i -e TARGET_DB="${target_db}" "${social_db_container}" sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  pg_restore -U "$POSTGRES_USER" -d "$TARGET_DB" --clean --if-exists --no-owner --no-privileges
' < "${dump_file}"

printf 'Restored %s into database %s\n' "${dump_file}" "${target_db}"

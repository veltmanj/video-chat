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

resolve_default_dump() {
  shopt -s nullglob
  local dumps=("${ROOT_DIR}"/backups/*.dump)
  shopt -u nullglob

  if [[ "${#dumps[@]}" -eq 0 ]]; then
    return 1
  fi

  ls -1t "${dumps[@]}" | head -n 1
}

resolve_dev_db_name() {
  local configured_name

  configured_name="$(grep '^SOCIAL_DB_DEV_NAME=' .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true)"
  if [[ -n "${configured_name}" ]]; then
    printf '%s\n' "${configured_name}"
    return 0
  fi

  printf 'videochat_dev\n'
}

dump_file="${1:-}"
target_db="$(resolve_dev_db_name)"

if [[ -z "${dump_file}" ]]; then
  if ! dump_file="$(resolve_default_dump)"; then
    echo 'Usage: ./scripts/reload-prod-social-db.sh <dump-file>' >&2
    echo 'No dump file was provided and no backups/*.dump files were found.' >&2
    exit 1
  fi
fi

"${SCRIPT_DIR}/restore-social-db.sh" "${dump_file}" "${target_db}"

social_db_container="$(find_running_container social-db)"
if [[ -z "${social_db_container}" ]]; then
  echo 'social-db is not running.' >&2
  exit 1
fi

active_db="$(docker exec "${social_db_container}" sh -lc 'printf %s "$POSTGRES_DB"' | tr -d '\r')"

if [[ "${active_db}" = "${target_db}" ]]; then
  printf 'Reloaded production data into the active dev database %s\n' "${target_db}"
else
  printf 'Reloaded production data into %s\n' "${target_db}"
  printf 'Start the dev stack with ./scripts/up-dev.sh to point the app at that database.\n'
fi

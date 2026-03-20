#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename -- "${ROOT_DIR}")}"

usage() {
  cat <<'EOF'
Usage: ./scripts/backup-social-db.sh [output-path]

Create a PostgreSQL custom-format dump from the running local social-db container.

Arguments:
  output-path    Optional path for the dump file. Defaults to backups/social-db-<db>-<timestamp>.dump.
  -h, --help     Show this help text.
EOF
}

find_running_container() {
  local service_name="${1}"

  docker ps \
    --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" \
    --filter "label=com.docker.compose.service=${service_name}" \
    --format '{{.Names}}' \
    | head -n 1
}

cd "${ROOT_DIR}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 1 ]]; then
  usage >&2
  exit 1
fi

social_db_container="$(find_running_container social-db)"
if [[ -z "${social_db_container}" ]]; then
  echo 'social-db is not running.' >&2
  exit 1
fi

db_name="$(docker exec "${social_db_container}" sh -lc 'printf %s "$POSTGRES_DB"' | tr -d '\r')"
timestamp="$(date '+%Y%m%d-%H%M%S')"
output_path="${1:-${ROOT_DIR}/backups/social-db-${db_name}-${timestamp}.dump}"

mkdir -p "$(dirname -- "${output_path}")"

docker exec -i "${social_db_container}" sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges
' > "${output_path}"

printf 'Created social database backup at %s\n' "${output_path}"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${2:-${ENV_FILE_DEFAULT}}"

dump_file="${1:-}"
[[ -n "${dump_file}" ]] || fail "Usage: ./scripts/k8s/restore-social-db.sh <dump-file> [k8s-env-file]"
[[ -f "${dump_file}" ]] || fail "Dump file not found: ${dump_file}"

pod_name="$(resolve_social_db_pod)"
[[ -n "${pod_name}" ]] || fail "Could not find the social-db pod in namespace ${K8S_NAMESPACE}."

kubectl exec --namespace "${K8S_NAMESPACE}" -i "${pod_name}" -- sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\''$POSTGRES_DB'\'' AND pid <> pg_backend_pid();" >/dev/null
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges
' < "${dump_file}"

printf 'Restored %s into database %s\n' "${dump_file}" "${SOCIAL_DB_NAME}"

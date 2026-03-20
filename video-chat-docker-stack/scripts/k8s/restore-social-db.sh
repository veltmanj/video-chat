#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/restore-social-db.sh [options] <dump-file> [k8s-env-file]

Restore a PostgreSQL dump into the social-db pod.

Options:
  --verbose       Show narrated step-by-step output (default)
  --quiet         Reduce output to command errors and the final summary
  --help          Show this help text
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --verbose)
      set_log_level verbose
      shift
      ;;
    --quiet)
      set_log_level quiet
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      break
      ;;
  esac
done

load_k8s_env "${2:-${ENV_FILE_DEFAULT}}"

dump_file="${1:-}"
[[ -n "${dump_file}" ]] || fail "Usage: ./scripts/k8s/restore-social-db.sh <dump-file> [k8s-env-file]"
[[ -f "${dump_file}" ]] || fail "Dump file not found: ${dump_file}"

pod_name="$(resolve_social_db_pod)"
[[ -n "${pod_name}" ]] || fail "Could not find the social-db pod in namespace ${K8S_NAMESPACE}."

log_step "Restoring social-db backup"
log_info "Pod: ${pod_name}"
log_info "Dump file: ${dump_file}"

kubectl exec --namespace "${K8S_NAMESPACE}" -i "${pod_name}" -- sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\''$POSTGRES_DB'\'' AND pid <> pg_backend_pid();" >/dev/null
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges
' < "${dump_file}"

printf 'Restored %s into database %s\n' "${dump_file}" "${SOCIAL_DB_NAME}"

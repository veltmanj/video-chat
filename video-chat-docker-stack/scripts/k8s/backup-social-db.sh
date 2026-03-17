#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"

pod_name="$(resolve_social_db_pod)"
[[ -n "${pod_name}" ]] || fail "Could not find the social-db pod in namespace ${K8S_NAMESPACE}."

timestamp="$(date '+%Y%m%d-%H%M%S')"
output_path="${2:-${ROOT_DIR}/backups/social-db-${SOCIAL_DB_NAME}-${timestamp}.dump}"
mkdir -p "$(dirname -- "${output_path}")"

kubectl exec --namespace "${K8S_NAMESPACE}" -i "${pod_name}" -- sh -lc '
  export PGPASSWORD="$POSTGRES_PASSWORD"
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges
' > "${output_path}"

printf 'Created social database backup at %s\n' "${output_path}"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"

usage() {
  cat <<'EOF'
Usage: ./scripts/configure-google-oauth.sh <google-oauth-client-id>

Update GOOGLE_OAUTH_CLIENT_ID in .env and rebuild the frontend container so the
runtime config picks up the new Google OAuth client id.

Arguments:
  google-oauth-client-id   Google OAuth web client id to write into .env.
  -h, --help               Show this help text.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 1
fi

CLIENT_ID="$1"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy .env.example first." >&2
  exit 1
fi

TMP_FILE="$(mktemp)"
awk -v client_id="${CLIENT_ID}" '
  BEGIN { updated = 0 }
  /^GOOGLE_OAUTH_CLIENT_ID=/ {
    print "GOOGLE_OAUTH_CLIENT_ID=" client_id
    updated = 1
    next
  }
  { print }
  END {
    if (!updated) {
      print "GOOGLE_OAUTH_CLIENT_ID=" client_id
    }
  }
' "${ENV_FILE}" >"${TMP_FILE}"
mv "${TMP_FILE}" "${ENV_FILE}"

echo "Updated GOOGLE_OAUTH_CLIENT_ID in ${ENV_FILE}"
echo "Rebuilding frontend container to apply runtime config..."

cd "${ROOT_DIR}"
docker compose up -d --build frontend

echo "Frontend rebuilt. Hard refresh the browser before testing login."

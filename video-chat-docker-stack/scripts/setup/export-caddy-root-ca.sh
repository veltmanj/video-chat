#!/usr/bin/env bash
set -euo pipefail

# Exports the current Caddy local root CA so client devices can trust HTTPS and WSS traffic.

usage() {
  cat <<'EOF'
Usage: ./scripts/export-caddy-root-ca.sh

Export the local Caddy root certificate from the running stack to a file in the
repository root so client devices can trust local HTTPS and WSS traffic.

Arguments:
  -h, --help    Show this help text.
EOF
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_FILE="${ROOT_DIR}/${CADDY_LOCAL_CA_FILENAME:-caddy-local-root-ca.crt}"

cd "${ROOT_DIR}"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ne 0 ]]; then
  usage >&2
  exit 1
fi

if [ -d "${OUTPUT_FILE}" ]; then
  rm -rf "${OUTPUT_FILE}"
fi

docker compose exec -T caddy sh -lc "cat /data/caddy/pki/authorities/local/root.crt" > "${OUTPUT_FILE}"

echo 'Exported Caddy local CA certificate:'
printf '  %s
' "${OUTPUT_FILE}"
echo
echo 'Install and trust this certificate on clients that need camera or websocket access over HTTPS.'

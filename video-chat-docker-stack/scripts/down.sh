#!/usr/bin/env bash
set -euo pipefail

# Stops the local stack without removing source-controlled configuration.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT_DIR}"
docker compose down

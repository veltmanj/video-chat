#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command docker

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"

docker build -t "${FRONTEND_IMAGE}" "${ROOT_DIR}/../video-chat-angular"
docker build -t "${BROKER_IMAGE}" "${ROOT_DIR}/../video-chat-rsocket-broker"
docker build -t "${BACKOFFICE_IMAGE}" "${ROOT_DIR}/../video-chat-backoffice"

if [[ "${K8S_PUSH_IMAGES:-false}" == "true" ]]; then
  docker push "${FRONTEND_IMAGE}"
  docker push "${BROKER_IMAGE}"
  docker push "${BACKOFFICE_IMAGE}"
fi

printf 'Frontend image: %s\n' "${FRONTEND_IMAGE}"
printf 'Broker image: %s\n' "${BROKER_IMAGE}"
printf 'Backoffice image: %s\n' "${BACKOFFICE_IMAGE}"

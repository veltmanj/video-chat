#!/usr/bin/env bash
set -euo pipefail

# Build the three application images from sibling repositories. This script is
# intentionally small because image naming and env resolution live in common.sh.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command docker

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/build-images.sh [options]

Build the frontend, broker, and backoffice images and optionally push them.

Options:
  --env <path>    Use a specific env file instead of k8s/k8s.env
  --verbose       Show narrated step-by-step output (default)
  --quiet         Reduce output to command errors and the final summary
  --help          Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
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
      fail "Unknown option: $1"
      ;;
  esac
done

load_k8s_env "${ENV_FILE}"

BUILD_PLATFORM="${K8S_BUILD_PLATFORM:-linux/amd64}"

log_step "Building application images"
log_info "Build platform: ${BUILD_PLATFORM}"

# Keep the build order explicit so logs are easy to scan when one repo fails.
log_info "Building frontend image ${FRONTEND_IMAGE}"
run_with_log_mode docker build --platform "${BUILD_PLATFORM}" -t "${FRONTEND_IMAGE}" "${ROOT_DIR}/../video-chat-angular"
log_info "Building broker image ${BROKER_IMAGE}"
run_with_log_mode docker build --platform "${BUILD_PLATFORM}" -t "${BROKER_IMAGE}" "${ROOT_DIR}/../video-chat-rsocket-broker"
log_info "Building backoffice image ${BACKOFFICE_IMAGE}"
run_with_log_mode docker build --platform "${BUILD_PLATFORM}" -t "${BACKOFFICE_IMAGE}" "${ROOT_DIR}/../video-chat-backoffice"

# Push is optional because local clusters often build directly into a local
# daemon, while GKE requires registry-backed images.
if [[ "${K8S_PUSH_IMAGES:-false}" == "true" ]]; then
  log_step "Pushing application images"
  log_info "Pushing frontend image"
  run_with_log_mode docker push "${FRONTEND_IMAGE}"
  log_info "Pushing broker image"
  run_with_log_mode docker push "${BROKER_IMAGE}"
  log_info "Pushing backoffice image"
  run_with_log_mode docker push "${BACKOFFICE_IMAGE}"
else
  log_skip "image push because K8S_PUSH_IMAGES is not true."
fi

printf 'Frontend image: %s\n' "${FRONTEND_IMAGE}"
printf 'Broker image: %s\n' "${BROKER_IMAGE}"
printf 'Backoffice image: %s\n' "${BACKOFFICE_IMAGE}"

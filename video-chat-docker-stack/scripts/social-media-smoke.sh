#!/usr/bin/env bash
set -euo pipefail

# Exercises the authenticated social media flow through the public HTTPS edge:
# me -> upload media -> create post -> feed -> media download -> DB attachment checks.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
HOST_VALUE=""
HTTPS_RESOLVE=()
TOKEN=""
MEDIA_FILE=""
KEEP_MEDIA_FILE=0
UPLOAD_CONTENT_TYPE=""
EXPECTED_MEDIA_KIND=""

cd "${ROOT_DIR}"

read_env_value() {
  local key="$1"
  local value
  value="$(grep -E "^${key}=" .env 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
  printf '%s' "${value}"
}

refresh_host_config() {
  HOST_VALUE="${VIDEOCHAT_HOST:-$(read_env_value "VIDEOCHAT_HOST")}"
  HOST_VALUE="${HOST_VALUE:-localhost}"
  HTTPS_RESOLVE=(--resolve "${HOST_VALUE}:443:127.0.0.1")
}

fail() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

usage() {
  cat >&2 <<'EOF'
Usage:
  SOCIAL_BEARER_TOKEN=<google-id-token> ./scripts/social-media-smoke.sh

Optional:
  SOCIAL_BEARER_TOKEN_FILE=/path/to/token.txt
  SOCIAL_SMOKE_MEDIA_FILE=/path/to/image-or-video
  SOCIAL_SMOKE_MEDIA_CONTENT_TYPE=image/png
  SOCIAL_SMOKE_POST_BODY='[ops smoke] custom marker'

Notes:
  - This script needs a real Google ID token accepted by the running backoffice.
  - Quick way to get one from the running app in your browser console:
      sessionStorage.getItem('pulse-room:google-id-token')
  - The script creates a smoke-test post and does not delete it.
EOF
}

write_default_png() {
  local output_path="$1"
  local base64_png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZk8AAAAASUVORK5CYII="

  if base64 --help 2>&1 | grep -q -- '--decode'; then
    printf '%s' "${base64_png}" | base64 --decode > "${output_path}"
  else
    printf '%s' "${base64_png}" | base64 -D > "${output_path}"
  fi
}

resolve_media_expectations() {
  local content_type="$1"

  case "${content_type}" in
    image/*)
      EXPECTED_MEDIA_KIND="IMAGE"
      ;;
    video/*)
      EXPECTED_MEDIA_KIND="VIDEO"
      ;;
    *)
      fail "Unsupported smoke-test content type: ${content_type}"
      ;;
  esac
}

api_request() {
  local method="$1"
  local url="$2"
  local body_file="$3"
  shift 3

  local headers_file
  local status
  headers_file="$(mktemp)"
  status="$(
    curl -sk --http1.1 --max-time 30 "${HTTPS_RESOLVE[@]}" \
      -X "${method}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -D "${headers_file}" \
      -o "${body_file}" \
      -w "%{http_code}" \
      "$@" \
      "${url}"
  )"

  if [[ "${status}" -lt 200 || "${status}" -ge 300 ]]; then
    echo "Request failed: ${method} ${url} -> HTTP ${status}" >&2
    sed -n '1,20p' "${headers_file}" >&2 || true
    sed -n '1,80p' "${body_file}" >&2 || true
    rm -f "${headers_file}"
    return 1
  fi

  rm -f "${headers_file}"
}

sql_scalar() {
  local sql="$1"
  docker compose exec -T social-db psql \
    -U "$(read_env_value "SOCIAL_DB_USER")" \
    -d "$(read_env_value "SOCIAL_DB_NAME")" \
    -Atc "${sql}"
}

cleanup() {
  if [[ "${KEEP_MEDIA_FILE}" != "1" && -n "${MEDIA_FILE}" && -f "${MEDIA_FILE}" ]]; then
    rm -f "${MEDIA_FILE}"
  fi
}

trap cleanup EXIT

require_command curl
require_command jq
require_command docker
require_command base64
require_command file

refresh_host_config

TOKEN="${SOCIAL_BEARER_TOKEN:-}"
if [[ -z "${TOKEN}" && -n "${SOCIAL_BEARER_TOKEN_FILE:-}" && -f "${SOCIAL_BEARER_TOKEN_FILE}" ]]; then
  TOKEN="$(tr -d '\r\n' < "${SOCIAL_BEARER_TOKEN_FILE}")"
fi

if [[ -z "${TOKEN}" ]]; then
  usage
  fail "SOCIAL_BEARER_TOKEN is required for the authenticated smoke test."
fi

docker compose ps >/dev/null
docker compose exec -T backoffice curl -fsS http://127.0.0.1:7901/actuator/health >/dev/null
docker compose exec -T minio curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null

if [[ -n "${SOCIAL_SMOKE_MEDIA_FILE:-}" ]]; then
  MEDIA_FILE="${SOCIAL_SMOKE_MEDIA_FILE}"
  [[ -f "${MEDIA_FILE}" ]] || fail "SOCIAL_SMOKE_MEDIA_FILE does not exist: ${MEDIA_FILE}"
  KEEP_MEDIA_FILE=1
  UPLOAD_CONTENT_TYPE="${SOCIAL_SMOKE_MEDIA_CONTENT_TYPE:-$(file --mime-type -b "${MEDIA_FILE}")}"
else
  MEDIA_FILE="$(mktemp "${TMPDIR:-/tmp}/social-media-smoke-XXXXXX.png")"
  write_default_png "${MEDIA_FILE}"
  UPLOAD_CONTENT_TYPE="image/png"
fi
resolve_media_expectations "${UPLOAD_CONTENT_TYPE}"

ME_BODY="$(mktemp)"
UPLOAD_BODY="$(mktemp)"
POST_BODY_FILE="$(mktemp)"
POST_RESPONSE_BODY="$(mktemp)"
FEED_BODY="$(mktemp)"
MEDIA_HEADERS="$(mktemp)"
MEDIA_DOWNLOAD="$(mktemp)"
trap 'cleanup; rm -f "${ME_BODY}" "${UPLOAD_BODY}" "${POST_BODY_FILE}" "${POST_RESPONSE_BODY}" "${FEED_BODY}" "${MEDIA_HEADERS}" "${MEDIA_DOWNLOAD}"' EXIT

echo "== viewer =="
api_request GET "https://${HOST_VALUE}/social-api/social/v1/me" "${ME_BODY}"
VIEWER_HANDLE="$(jq -er '.me.handle' "${ME_BODY}")"
VIEWER_NAME="$(jq -er '.me.displayName' "${ME_BODY}")"
echo "viewer handle: ${VIEWER_HANDLE}"
echo "viewer name: ${VIEWER_NAME}"

echo
echo "== upload media =="
api_request POST "https://${HOST_VALUE}/social-api/social/v1/media/uploads" "${UPLOAD_BODY}" \
  -F "file=@${MEDIA_FILE};type=${UPLOAD_CONTENT_TYPE}"
MEDIA_ID="$(jq -er '.id' "${UPLOAD_BODY}")"
MEDIA_KIND="$(jq -er '.kind' "${UPLOAD_BODY}")"
MEDIA_MIME_TYPE="$(jq -er '.mimeType' "${UPLOAD_BODY}")"
MEDIA_SIZE="$(jq -er '.fileSize' "${UPLOAD_BODY}")"
echo "media id: ${MEDIA_ID}"
echo "media kind: ${MEDIA_KIND}"
echo "media type: ${MEDIA_MIME_TYPE}"
echo "media size: ${MEDIA_SIZE}"
[[ "${MEDIA_KIND}" == "${EXPECTED_MEDIA_KIND}" ]] || fail "Expected uploaded media kind ${EXPECTED_MEDIA_KIND}, got ${MEDIA_KIND}"
[[ "${MEDIA_MIME_TYPE}" == "${UPLOAD_CONTENT_TYPE}" ]] || fail "Expected uploaded media mimeType ${UPLOAD_CONTENT_TYPE}, got ${MEDIA_MIME_TYPE}"

POST_TEXT="${SOCIAL_SMOKE_POST_BODY:-[ops smoke] media upload $(date -u +%Y-%m-%dT%H:%M:%SZ)}"
jq -n --arg body "${POST_TEXT}" --arg media_id "${MEDIA_ID}" \
  '{body: $body, mediaIds: [$media_id]}' > "${POST_BODY_FILE}"

echo
echo "== create post =="
api_request POST "https://${HOST_VALUE}/social-api/social/v1/posts" "${POST_RESPONSE_BODY}" \
  -H "Content-Type: application/json" \
  --data-binary "@${POST_BODY_FILE}"
POST_ID="$(jq -er '.id' "${POST_RESPONSE_BODY}")"
POST_MEDIA_ID="$(jq -er '.media[0].id' "${POST_RESPONSE_BODY}")"
POST_AUTHOR_HANDLE="$(jq -er '.authorHandle' "${POST_RESPONSE_BODY}")"
echo "post id: ${POST_ID}"
echo "post author: ${POST_AUTHOR_HANDLE}"
[[ "${POST_MEDIA_ID}" == "${MEDIA_ID}" ]] || fail "Created post does not reference uploaded media ${MEDIA_ID}."
[[ "${POST_AUTHOR_HANDLE}" == "${VIEWER_HANDLE}" ]] || fail "Created post author ${POST_AUTHOR_HANDLE} does not match viewer ${VIEWER_HANDLE}."

echo
echo "== feed lookup =="
api_request GET "https://${HOST_VALUE}/social-api/social/v1/feed" "${FEED_BODY}"
jq -e --arg post_id "${POST_ID}" '.posts[] | select(.id == $post_id)' "${FEED_BODY}" >/dev/null \
  || fail "Newly created post ${POST_ID} is missing from the feed response."
echo "feed contains post: ${POST_ID}"

echo
echo "== media download =="
MEDIA_STATUS="$(
  curl -sk --http1.1 --max-time 30 "${HTTPS_RESOLVE[@]}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -D "${MEDIA_HEADERS}" \
    -o "${MEDIA_DOWNLOAD}" \
    -w "%{http_code}" \
    "https://${HOST_VALUE}/social-api/social/v1/media/${MEDIA_ID}/content"
)"
[[ "${MEDIA_STATUS}" == "200" ]] || fail "Media download returned HTTP ${MEDIA_STATUS}"
grep -qi "^content-type: ${UPLOAD_CONTENT_TYPE}" "${MEDIA_HEADERS}" || fail "Media download did not return content-type ${UPLOAD_CONTENT_TYPE}."
DOWNLOADED_BYTES="$(wc -c < "${MEDIA_DOWNLOAD}" | tr -d ' ')"
[[ "${DOWNLOADED_BYTES}" -gt 0 ]] || fail "Downloaded media is empty."
echo "downloaded bytes: ${DOWNLOADED_BYTES}"

echo
echo "== database checks =="
DB_MEDIA_COUNT="$(sql_scalar "select count(*) from media_assets where id = '${MEDIA_ID}';")"
DB_POST_MEDIA_COUNT="$(sql_scalar "select count(*) from post_media_assets where post_id = '${POST_ID}' and media_asset_id = '${MEDIA_ID}';")"
DB_OWNER_HANDLE="$(
  sql_scalar "
    select p.handle
    from media_assets m
    join profiles p on p.id = m.owner_profile_id
    where m.id = '${MEDIA_ID}';
  "
)"
[[ "${DB_MEDIA_COUNT}" == "1" ]] || fail "Expected one media_assets row for ${MEDIA_ID}, got ${DB_MEDIA_COUNT}."
[[ "${DB_POST_MEDIA_COUNT}" == "1" ]] || fail "Expected one post_media_assets row linking ${POST_ID} to ${MEDIA_ID}, got ${DB_POST_MEDIA_COUNT}."
[[ "${DB_OWNER_HANDLE}" == "${VIEWER_HANDLE}" ]] || fail "Expected media owner handle ${VIEWER_HANDLE}, got ${DB_OWNER_HANDLE}."
echo "media_assets row: ${DB_MEDIA_COUNT}"
echo "post_media_assets row: ${DB_POST_MEDIA_COUNT}"
echo "media owner handle: ${DB_OWNER_HANDLE}"

echo
echo "Social media smoke test passed through https://${HOST_VALUE}"
echo "created post: ${POST_ID}"
echo "uploaded media: ${MEDIA_ID}"

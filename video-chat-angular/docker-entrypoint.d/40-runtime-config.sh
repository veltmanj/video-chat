#!/bin/sh
set -eu

cat <<EOF >/usr/share/nginx/html/assets/runtime-config.js
window.__VIDEOCHAT_CONFIG__ = {
  googleClientId: "${GOOGLE_OAUTH_CLIENT_ID:-}",
  appMode: "${VIDEOCHAT_APP_MODE:-production}"
};
EOF

#!/bin/sh
set -eu

cat <<EOF >/usr/share/nginx/html/assets/runtime-config.js
window.__VIDEOCHAT_CONFIG__ = {
  googleClientId: "${GOOGLE_OAUTH_CLIENT_ID:-}",
  appMode: "${VIDEOCHAT_APP_MODE:-production}",
  aiAgentEnabled: "${VIDEOCHAT_AI_AGENT_ENABLED:-false}",
  aiAgentEndpoint: "${VIDEOCHAT_AI_AGENT_ENDPOINT:-/social-api/social/v1/rooms/{roomId}/assistant-replies}",
  aiAgentName: "${VIDEOCHAT_AI_AGENT_NAME:-Pulse Copilot}",
  aiAgentMention: "${VIDEOCHAT_AI_AGENT_MENTION:-@pulse}"
};
EOF

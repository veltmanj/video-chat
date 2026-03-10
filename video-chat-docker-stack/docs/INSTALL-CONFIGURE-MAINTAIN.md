# Docker Stack Install, Configure, and Maintain Guide

## 1. Purpose

This repository packages the full local deployment stack for the video chat system:

- Angular frontend
- Spring Boot RSocket broker
- Spring Boot backoffice
- HashiCorp Vault for provider JWKS storage
- Caddy reverse proxy with locally trusted HTTPS

The stack is intended for local development, LAN testing, and device validation on phones or tablets that need HTTPS and WSS.

## 2. Supported stack

- Docker Compose v2
- Vault `1.17`
- Caddy `2.10.2`
- Angular frontend built from `../video-chat-angular`
- Broker built from `../video-chat-rsocket-broker`
- Backoffice built from `../video-chat-backoffice`

## 3. Prerequisites

Assume `${REPO_ROOT}` is the directory containing the sibling repositories.

Verify the required tools:

```bash
docker version
docker compose version
```

You also need the sibling repositories present at:

- `../video-chat-angular`
- `../video-chat-rsocket-broker`
- `../video-chat-backoffice`

## 4. Initial installation

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
cp .env.example .env
```

Edit `.env` and set at least:

- `VIDEOCHAT_HOST=localhost` for same-machine usage
- `VIDEOCHAT_HOST=<LAN_IP>` for access from another device on the same network
- `GOOGLE_OAUTH_CLIENT_ID=<your_google_web_client_id>` for Google login
- `VIDEOCHAT_APP_MODE=production` unless you want developer diagnostics on the login page

To obtain a Google web client ID:

1. Open Google Cloud Console and select your project.
2. Go to `Google Auth Platform > Branding` and complete the consent-screen basics.
3. Go to `Google Auth Platform > Audience` and choose `External` unless this app is only for your Workspace org.
4. Add your Google account as a test user if the app is not published.
5. Go to `Google Auth Platform > Clients`.
6. Create a `Web application` client.
7. Add `https://<VIDEOCHAT_HOST>` as an authorized JavaScript origin.
8. Add `https://<VIDEOCHAT_HOST>` as an authorized redirect URI.

The frontend uses `window.location.origin` as its OAuth callback, so the redirect URI should match the app origin exactly.

Validate the stack before startup:

```bash
./scripts/validate.sh
```

Start everything:

```bash
./scripts/up.sh
```

Start everything in development mode:

```bash
./scripts/up-dev.sh
```

Stop everything:

```bash
./scripts/down.sh
```

## 5. Configuration reference

### 5.1 `.env`

Key variables:

- `VIDEOCHAT_HOST`: host or LAN IP used by Caddy certificate and redirects
- `GOOGLE_OAUTH_CLIENT_ID`: Google OAuth web client ID injected into the frontend at runtime
- `VIDEOCHAT_APP_MODE`: frontend login-page mode, `production` or `development`
- `CADDY_IMAGE`: Caddy image tag, default `caddy:2.10.2`
- `VAULT_IMAGE`: Vault image tag, default `hashicorp/vault:1.17`
- `FRONTEND_CONTAINER_NAME`: frontend container name
- `BROKER_CONTAINER_NAME`: broker container name
- `BACKOFFICE_CONTAINER_NAME`: backoffice container name
- `CADDY_CONTAINER_NAME`: Caddy container name
- `VAULT_CONTAINER_NAME`: Vault container name
- `VAULT_INIT_CONTAINER_NAME`: Vault bootstrap container name
- `CADDY_LOCAL_CA_FILENAME`: exported local CA filename
- `VAULT_HOST_PORT`: local Vault port binding, default `8200`
- `VAULT_DEV_ROOT_TOKEN_ID`: local Vault root token
- `VAULT_KV_MOUNT`: Vault KV-v2 mount, default `secret`
- `VAULT_PROVIDER_FIELD`: field name used to store JWKS JSON, default `jwks_json`
- `VAULT_GOOGLE_SECRET_PATH`, `VAULT_APPLE_SECRET_PATH`, `VAULT_X_SECRET_PATH`: Vault secret paths used by the broker
- `VAULT_GOOGLE_JWKS_URL`, `VAULT_APPLE_JWKS_URL`, `VAULT_X_JWKS_URL`: JWKS bootstrap URLs
- `BROKER_JWT_ENABLED`, `BROKER_JWT_CACHE_TTL`, `BROKER_JWT_CLOCK_SKEW`: broker JWT validation controls
- `BROKER_JWT_GOOGLE_ENABLED`, `BROKER_JWT_APPLE_ENABLED`, `BROKER_JWT_X_ENABLED`: per-provider broker toggles
- `BROKER_JWT_GOOGLE_AUDIENCE`: optional Google audience pin; usually set this equal to `GOOGLE_OAUTH_CLIENT_ID`

Recommended defaults:

- Keep `BROKER_JWT_GOOGLE_ENABLED=true` for the Google login flow.
- Keep `BROKER_JWT_APPLE_ENABLED=false` and `BROKER_JWT_X_ENABLED=false` unless those providers are actually configured and in use.
- Set `BROKER_JWT_GOOGLE_AUDIENCE` to the same value as `GOOGLE_OAUTH_CLIENT_ID` once the client ID is known.

### 5.2 `docker-compose.yml`

Responsibilities:

- builds the frontend, broker, and backoffice from sibling repos
- starts a local Vault dev server and a one-shot JWKS bootstrap job
- attaches all services to the shared `videochat-network`
- waits for Vault bootstrap, broker, and backoffice health checks before starting Caddy
- mounts the Caddy config and exported local CA certificate

### 5.2.1 Security drill

Run the live auth-negative drill from the stack root:

```bash
./scripts/security-drill.sh
```

The drill attacks the broker with malformed tokens and, when `BROKER_JWT_X_ENABLED=true`, also with an injected attacker-controlled JWKS. If a probe succeeds unexpectedly, the script hardens `.env`, rebuilds the broker, and re-runs the drill. If the problem persists, it stops `broker` and `caddy` to contain exposure.

### 5.3 `Caddyfile`

Responsibilities:

- redirects HTTP to HTTPS except for direct certificate download
- terminates HTTPS using `tls internal`
- proxies `/rsocket` to the broker with explicit WebSocket header forwarding
- proxies `/backoffice-api/*` to the backoffice REST API
- proxies `/backoffice-rsocket*` to the backoffice RSocket path
- serves `/local-ca.crt` for trust bootstrap on client devices

## 6. Runtime URLs

Assuming `VIDEOCHAT_HOST=<VIDEOCHAT_HOST>`:

- app: `https://<VIDEOCHAT_HOST>/`
- broker websocket endpoint: `wss://<VIDEOCHAT_HOST>/rsocket`
- backoffice API: `https://<VIDEOCHAT_HOST>/backoffice-api/api/rooms`
- Vault API: `http://127.0.0.1:${VAULT_HOST_PORT:-8200}`
- CA download: `http://<VIDEOCHAT_HOST>/local-ca.crt`

## 6.1 Vault provider key bootstrap

On startup, the `vault-init` job ensures the configured KV-v2 mount exists and writes provider JWKS documents into Vault:

- Google uses `VAULT_GOOGLE_JWKS_URL` by default.
- Apple uses `VAULT_APPLE_JWKS_URL` by default.
- X uses `VAULT_X_JWKS_URL` when set.
- Any provider can be overridden by adding `google-jwks.json`, `apple-jwks.json`, or `x-jwks.json` to `vault/secrets/`.

The broker reads those secrets from Vault instead of from static public-key configuration.

## 7. Validation and test commands

Validate compose and Caddy configuration:

```bash
./scripts/validate.sh
```

Render the final compose config:

```bash
docker compose config
```

Start or rebuild the full stack:

```bash
docker compose up -d --build
```

Start the stack with frontend development diagnostics enabled:

```bash
./scripts/up-dev.sh
```

Update the frontend with a Google OAuth client ID and rebuild only that service:

```bash
./scripts/configure-google-oauth.sh <your-google-web-client-id>
```

Recreate only Caddy after a proxy or host change:

```bash
docker compose up -d --force-recreate caddy
```

Run the built-in health and route checks:

```bash
./scripts/check.sh
```

## 8. Monitoring and debugging commands

Show service status:

```bash
docker compose ps
```

Follow logs for the whole stack:

```bash
./scripts/logs.sh
```

Follow logs for one service:

```bash
./scripts/logs.sh caddy
./scripts/logs.sh broker
./scripts/logs.sh backoffice
./scripts/logs.sh frontend
```

Inspect the docker network:

```bash
docker network inspect videochat-network
```

Inspect a container definition:

```bash
docker inspect videochat-caddy
docker inspect videochat-vault
```

Check Vault health directly:

```bash
curl -fsS http://127.0.0.1:${VAULT_HOST_PORT:-8200}/v1/sys/health
```

Check broker health directly:

```bash
curl -fsS http://127.0.0.1:9898/actuator/health
```

Check backoffice health directly:

```bash
curl -fsS http://127.0.0.1:7901/actuator/health
```

Check the public HTTPS entrypoint:

```bash
curl -skI --http1.1 https://$VIDEOCHAT_HOST/
```

Check the public backoffice API through Caddy:

```bash
curl -sk --http1.1 https://$VIDEOCHAT_HOST/backoffice-api/api/rooms
```

Check the local certificate download endpoint:

```bash
curl -skI https://$VIDEOCHAT_HOST/local-ca.crt
curl -sI http://$VIDEOCHAT_HOST/local-ca.crt
```

Check the websocket upgrade path toward the broker:

```bash
curl -skv --http1.1   -H 'Connection: Upgrade'   -H 'Upgrade: websocket'   -H 'Sec-WebSocket-Version: 13'   -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='   -H 'Sec-WebSocket-Protocol: rsocket'   https://$VIDEOCHAT_HOST/rsocket
```

Validate the active Caddy config file with the runtime image:

```bash
docker run --rm   -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro"   ${CADDY_IMAGE:-caddy:2.10.2}   caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

## 9. Certificate trust workflow

Export the local CA certificate after Caddy has started:

```bash
./scripts/export-caddy-root-ca.sh
```

This writes `${CADDY_LOCAL_CA_FILENAME}` in the repo root. Install and trust it on external devices before testing camera, microphone, or websocket flows over HTTPS.

For iPhone or iPad, after installing the profile, enable trust under:

- `Settings > General > About > Certificate Trust Settings`

## 10. Troubleshooting

### 10.1 Browser loads HTTP but not HTTPS

Run:

```bash
docker compose ps
./scripts/logs.sh caddy
curl -skI https://$VIDEOCHAT_HOST/
```

### 10.2 HTTPS works but websocket connections fail

Run:

```bash
curl -skv --http1.1   -H 'Connection: Upgrade'   -H 'Upgrade: websocket'   -H 'Sec-WebSocket-Version: 13'   -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='   -H 'Sec-WebSocket-Protocol: rsocket'   https://$VIDEOCHAT_HOST/rsocket
./scripts/logs.sh caddy
./scripts/logs.sh broker
```

### 10.3 Backoffice API is unavailable through Caddy

Run:

```bash
curl -fsS http://127.0.0.1:7901/actuator/health
curl -sk --http1.1 https://$VIDEOCHAT_HOST/backoffice-api/api/rooms
./scripts/logs.sh backoffice
./scripts/logs.sh caddy
```

### 10.4 Caddy config changes are ignored

Run:

```bash
./scripts/validate.sh
docker compose up -d --force-recreate caddy
./scripts/logs.sh caddy
```

### 10.5 Client device does not trust the certificate

Run:

```bash
./scripts/export-caddy-root-ca.sh
curl -sI http://$VIDEOCHAT_HOST/local-ca.crt
```

Then reinstall the exported certificate on the device and re-enable trust.

## 11. Maintenance checklist

After any compose, proxy, or service upgrade:

```bash
./scripts/validate.sh
docker compose up -d --build
./scripts/check.sh
```

Before testing from another device:

- Confirm `.env` contains the correct `VIDEOCHAT_HOST`.
- Recreate Caddy if the host value changed.
- Re-export and reinstall the CA certificate if the local CA storage was reset.

# Docker Stack Install, Configure, and Maintain Guide

## 1. Purpose

This repository packages the full local deployment stack for the video chat system:

- Angular frontend
- Spring Boot RSocket broker
- Spring Boot backoffice
- Prometheus and blackbox-exporter for metrics and endpoint probes
- Loki and Promtail for log aggregation
- Grafana for dashboards
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
- `VIDEOCHAT_AI_AGENT_ENABLED=true` and `BACKOFFICE_AI_ENABLED=true` to switch on the shared AI room assistant
- `BACKOFFICE_AI_API_KEY=<your_openai_api_key>` for the shared assistant backend call
- `VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD=<strong_local_password>` if you want to pin the Grafana bootstrap credentials

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

This uses an isolated social database name and volume for development and runs a one-shot seed job with 15 sample profiles. If the social schema is not present yet, the seed job applies the SQL migrations first.

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
- `VIDEOCHAT_AI_AGENT_ENABLED`, `VIDEOCHAT_AI_AGENT_ENDPOINT`, `VIDEOCHAT_AI_AGENT_NAME`, `VIDEOCHAT_AI_AGENT_MENTION`: frontend runtime AI assistant settings
- `CADDY_IMAGE`: Caddy image tag, default `caddy:2.10.2`
- `VAULT_IMAGE`: Vault image tag, default `hashicorp/vault:1.17`
- `PROMETHEUS_IMAGE`, `PROMETHEUS_HOST_PORT`: Prometheus image and local UI binding
- `BLACKBOX_EXPORTER_IMAGE`: probe exporter image used by Prometheus
- `LOKI_IMAGE`: Loki image used for Docker log aggregation
- `PROMTAIL_IMAGE`: Promtail image used to ship Docker logs into Loki
- `GRAFANA_IMAGE`, `GRAFANA_HOST_PORT`: Grafana image and local UI binding
- `GRAFANA_ADMIN_USER`: Grafana login username
- `GRAFANA_ADMIN_PASSWORD`: optional compatibility alias for the Grafana bootstrap password
- `FRONTEND_CONTAINER_NAME`: frontend container name
- `BROKER_CONTAINER_NAME`: broker container name
- `BACKOFFICE_CONTAINER_NAME`: backoffice container name
- `CADDY_CONTAINER_NAME`: Caddy container name
- `VAULT_CONTAINER_NAME`: Vault container name
- `VAULT_INIT_CONTAINER_NAME`: Vault bootstrap container name
- `CADDY_LOCAL_CA_FILENAME`: exported local CA filename
- `VAULT_HOST_PORT`: local Vault port binding, default `8200`
- `VAULT_DEV_ROOT_TOKEN_ID`: local-dev-only Vault bootstrap token
- `VAULT_KV_MOUNT`: Vault KV-v2 mount, default `secret`
- `VAULT_PROVIDER_FIELD`: field name used to store JWKS JSON, default `jwks_json`
- `VAULT_BOOTSTRAP_SOCIAL_DB_PASSWORD`, `VAULT_BOOTSTRAP_MINIO_ROOT_PASSWORD`, `VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD`: optional bootstrap secrets written into Vault by `vault-init`
- `VAULT_SOCIAL_DB_SECRET_PATH`, `VAULT_MINIO_SECRET_PATH`, `VAULT_GRAFANA_SECRET_PATH`: Vault KV paths for the stack-managed runtime secrets
- `VAULT_GOOGLE_SECRET_PATH`, `VAULT_APPLE_SECRET_PATH`, `VAULT_X_SECRET_PATH`: Vault secret paths used by the broker
- `VAULT_GOOGLE_JWKS_URL`, `VAULT_APPLE_JWKS_URL`, `VAULT_X_JWKS_URL`: JWKS bootstrap URLs
- `BROKER_JWT_ENABLED`, `BROKER_JWT_CACHE_TTL`, `BROKER_JWT_CLOCK_SKEW`: broker JWT validation controls
- `SOCIAL_DB_IMAGE`, `SOCIAL_DB_CONTAINER_NAME`, `SOCIAL_DB_NAME`, `SOCIAL_DB_USER`: primary social database settings
- `SOCIAL_DB_VOLUME_NAME`: active social database volume name
- `SOCIAL_DB_SEED_CONTAINER_NAME`, `SOCIAL_DB_SEED_ENABLED`, `SOCIAL_DB_SEED_FORCE`: development seed job controls
- `SOCIAL_DB_DEV_NAME`, `SOCIAL_DB_DEV_CONTAINER_NAME`, `SOCIAL_DB_DEV_VOLUME_NAME`, `SOCIAL_DB_DEV_SEED_CONTAINER_NAME`: isolated dev social database settings
- `BROKER_JWT_GOOGLE_ENABLED`, `BROKER_JWT_APPLE_ENABLED`, `BROKER_JWT_X_ENABLED`: per-provider broker toggles
- `BROKER_JWT_GOOGLE_AUDIENCE`: optional Google audience pin; usually set this equal to `GOOGLE_OAUTH_CLIENT_ID`
- `BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE`: optional Google audience pin used by the social REST APIs
- `BACKOFFICE_AI_ENABLED`, `BACKOFFICE_AI_API_KEY`, `BACKOFFICE_AI_MODEL`, `BACKOFFICE_AI_ASSISTANT_NAME`: shared AI assistant settings

Recommended defaults:

- Keep `BROKER_JWT_GOOGLE_ENABLED=true` for the Google login flow.
- Keep `BROKER_JWT_APPLE_ENABLED=false` and `BROKER_JWT_X_ENABLED=false` unless those providers are actually configured and in use.
- Set `BROKER_JWT_GOOGLE_AUDIENCE` to the same value as `GOOGLE_OAUTH_CLIENT_ID` once the client ID is known.
- Leave `SOCIAL_DB_SEED_ENABLED=false` in `.env`; `./scripts/up-dev.sh` enables it only for the dev-mode startup invocation.
- The implementation scripts are grouped by concern under [`scripts/README.md`](../scripts/README.md); the familiar top-level commands in `./scripts/*.sh` remain stable wrappers for local usage and container mounts.

### 5.2 `docker-compose.yml`

Responsibilities:

- builds the frontend, broker, and backoffice from sibling repos
- starts a local Vault dev server, a one-shot Vault bootstrap job for JWKS and runtime secrets, and an optional dev-only social seed job
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
- Prometheus UI: `http://127.0.0.1:${PROMETHEUS_HOST_PORT:-9090}`
- Grafana UI: `http://127.0.0.1:${GRAFANA_HOST_PORT:-3000}`
- CA download: `http://<VIDEOCHAT_HOST>/local-ca.crt`

## 6.1 Vault-backed runtime bootstrap

On startup, the `vault-init` job ensures the configured KV-v2 mount exists and then:

- writes provider JWKS documents into Vault
- writes the social database password, MinIO root password, and Grafana admin password into Vault
- renders the current runtime secret files into the persistent `vault-runtime` volume
- mints a scoped broker token that can read only the configured JWT provider paths

Concrete bootstrap sequence:

1. `vault` starts in local dev mode with the configured `VAULT_DEV_ROOT_TOKEN_ID`.
2. `vault-init` connects with that token, ensures the configured KV-v2 mount exists, and creates `/vault/runtime` on the shared `vault-runtime` volume.
3. For each runtime secret, `vault-init` resolves a value in this order: explicit `VAULT_BOOTSTRAP_*` env var, matching file under `vault/secrets/`, previously rendered file in `vault-runtime`, generated random fallback.
4. `vault-init` writes those resolved values into Vault KV so Vault remains the source of truth for the current run.
5. `vault-init` renders the concrete secret fields back out into `/vault/runtime/*` files for services that only know how to read env vars or `*_FILE` inputs.
6. `vault-init` writes a least-privilege Vault policy for the broker's JWT-provider reads and mints a fresh scoped token into `/vault/runtime/broker-jwt-vault-token`.
7. The service-specific launcher scripts under `vault/scripts/start-*-with-vault.sh` read those runtime files and export the env vars each container expects immediately before starting the real process.

- Google uses `VAULT_GOOGLE_JWKS_URL` by default.
- Apple uses `VAULT_APPLE_JWKS_URL` by default.
- X uses `VAULT_X_JWKS_URL` when set.
- Any provider can be overridden by adding `google-jwks.json`, `apple-jwks.json`, or `x-jwks.json` to `vault/secrets/`.

Runtime secret precedence is:

- explicit `VAULT_BOOTSTRAP_*` values from `.env`
- `GRAFANA_ADMIN_PASSWORD` for Grafana only, when `VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD` is empty
- matching files in `vault/secrets/`
- persisted values already present in the `vault-runtime` volume
- a newly generated random secret

The broker reads provider keys from Vault using a scoped token rendered by `vault-init` instead of sharing the Vault root token with the runtime container.

Rendered runtime files and consumers:

- `/vault/runtime/social-db-password`: consumed by `social-db` via `POSTGRES_PASSWORD_FILE`, by `backoffice` via `SPRING_DATASOURCE_PASSWORD_FILE`, and by the dev seed job via `PGPASSWORD_FILE`
- `/vault/runtime/minio-root-password`: consumed by `minio` via `MINIO_ROOT_PASSWORD_FILE` and by `backoffice` via `BACKOFFICE_SOCIAL_MEDIA_SECRET_KEY_FILE`
- `/vault/runtime/grafana-admin-password`: consumed by Grafana via `GF_SECURITY_ADMIN_PASSWORD__FILE`
- `/vault/runtime/broker-jwt-vault-token`: consumed by the broker launcher and exported as `BROKER_JWT_VAULT_TOKEN`

Why both Vault and runtime files exist:

- Vault is the canonical store used for JWKS data and for the broker's runtime key lookups.
- Several off-the-shelf images in this stack do not speak Vault directly and only accept env vars or `*_FILE` inputs.
- The shared `vault-runtime` volume is the handoff point between the one-shot bootstrap job and those long-running containers.
- Because that volume persists across container recreation, locally generated passwords stay stable unless you explicitly rotate them.

If Grafana generated its password and you need to inspect it locally, run:

```bash
docker compose exec -T grafana sh -lc 'cat /vault/runtime/grafana-admin-password'
```

## 7. Validation and test commands

Validate compose and Caddy configuration:

```bash
./scripts/validate.sh
```

Render the final compose config:

```bash
docker compose config
```

Clean-sheet reset with local secret rotation:

```bash
./scripts/cleanup.sh --rotate-secrets --rebuild
```

Start or rebuild the full stack:

```bash
docker compose up -d --build
```

Start the stack with frontend development diagnostics enabled:

```bash
./scripts/up-dev.sh
```

Force a fresh dev social dataset:

```bash
SOCIAL_DB_SEED_FORCE=true ./scripts/up-dev.sh
```

Back up the running social database:

```bash
./scripts/backup-social-db.sh
```

Restore a dump into the current running social database:

```bash
./scripts/restore-social-db.sh ./backups/social-db-<timestamp>.dump
```

Restore a production dump into a different local database name:

```bash
./scripts/restore-social-db.sh ./backups/prod.dump videochat_dev
```

Restore a production dump directly into the isolated dev database:

```bash
./scripts/reload-prod-social-db.sh ./backups/prod.dump
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

Run the monitoring smoke test:

```bash
./scripts/monitoring-smoke.sh
```

Open Grafana:

```bash
open http://127.0.0.1:${GRAFANA_HOST_PORT:-3000}
```

Run the authenticated social media smoke test:

```bash
SOCIAL_BEARER_TOKEN=<google-id-token> ./scripts/social-media-smoke.sh
```

## 8. Monitoring and debugging commands

Show service status:

```bash
docker compose ps
```

Check Prometheus readiness:

```bash
curl -fsS http://127.0.0.1:${PROMETHEUS_HOST_PORT:-9090}/-/ready
```

Check Grafana health:

```bash
curl -fsS http://127.0.0.1:${GRAFANA_HOST_PORT:-3000}/api/health
```

Inspect Loki labels through the query API:

```bash
docker run --rm --network videochat-network curlimages/curl:8.8.0 -fsS http://loki:3100/loki/api/v1/labels
```

Run the end-to-end monitoring smoke test:

```bash
./scripts/monitoring-smoke.sh
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

Run the authenticated social media smoke test through Caddy:

```bash
SOCIAL_BEARER_TOKEN=<google-id-token> ./scripts/social-media-smoke.sh
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

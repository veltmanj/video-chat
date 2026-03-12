# Video Chat Docker Stack

Docker Compose stack for the Angular frontend, RSocket broker, Spring Boot backoffice, HashiCorp Vault, Prometheus, Loki, Grafana, and Caddy reverse proxy with locally trusted HTTPS.

## Included services

- Angular frontend
- RSocket broker
- Backoffice service
- Shared AI room assistant through the backoffice
- Prometheus metrics and blackbox probes
- Loki log storage with Promtail shipping
- Grafana dashboards
- HashiCorp Vault with provider JWKS bootstrap
- Caddy reverse proxy with `tls internal`

## Quick start

Assume `${REPO_ROOT}` is the directory containing this repository.

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
cp .env.example .env
# set VIDEOCHAT_HOST to your LAN IP if other devices need to connect
./scripts/validate.sh
./scripts/up.sh
```

## Runtime URLs

- App: `https://<VIDEOCHAT_HOST>`
- RSocket broker: `wss://<VIDEOCHAT_HOST>/rsocket`
- Backoffice REST API: `https://<VIDEOCHAT_HOST>/backoffice-api/api/rooms`
- Shared AI assistant endpoint: `https://<VIDEOCHAT_HOST>/social-api/social/v1/rooms/<roomId>/assistant-replies`
- Vault API: `http://127.0.0.1:${VAULT_HOST_PORT:-8200}`
- Prometheus UI: `http://127.0.0.1:${PROMETHEUS_HOST_PORT:-9090}`
- Grafana: `http://127.0.0.1:${GRAFANA_HOST_PORT:-3000}`
- Local CA download: `http://<VIDEOCHAT_HOST>/local-ca.crt`

## Common operations

Bring the stack up:

```bash
./scripts/up.sh
```

Bring the stack up in development mode:

```bash
./scripts/up-dev.sh
```

Back up the social database:

```bash
./scripts/backup-social-db.sh
```

Reload a dump into the running local social database:

```bash
./scripts/restore-social-db.sh ./backups/social-db-<timestamp>.dump
```

Reload a production dump into the isolated dev database:

```bash
./scripts/reload-prod-social-db.sh ./backups/social-db-<timestamp>.dump
```

Stop it:

```bash
./scripts/down.sh
```

Validate Compose and Caddy config:

```bash
./scripts/validate.sh
```

Check service health and published ports:

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

Tail logs:

```bash
./scripts/logs.sh
./scripts/logs.sh caddy
```

## Configuration

Main configuration lives in `.env`, `docker-compose.yml`, and `Caddyfile`.

Key variables:

- `VIDEOCHAT_HOST`: hostname or LAN IP presented by Caddy
- `GOOGLE_OAUTH_CLIENT_ID`: Google OAuth web client ID injected into the frontend at container startup
- `VIDEOCHAT_APP_MODE`: frontend app mode, `production` by default, `development` to show setup diagnostics on the login page
- `VIDEOCHAT_AI_AGENT_ENABLED`, `VIDEOCHAT_AI_AGENT_ENDPOINT`, `VIDEOCHAT_AI_AGENT_NAME`, `VIDEOCHAT_AI_AGENT_MENTION`: frontend runtime AI settings
- `CADDY_IMAGE`: Caddy image tag, defaults to `caddy:2.10.2`
- `CADDY_LOCAL_CA_FILENAME`: exported local CA certificate filename
- `PROMETHEUS_IMAGE`, `PROMETHEUS_HOST_PORT`: Prometheus image and local UI port
- `BLACKBOX_EXPORTER_IMAGE`: blackbox probe image used by Prometheus
- `LOKI_IMAGE`: Loki image for centralized log storage
- `PROMTAIL_IMAGE`: Promtail image for Docker log shipping
- `GRAFANA_IMAGE`, `GRAFANA_HOST_PORT`: Grafana image and local UI port
- `GRAFANA_ADMIN_USER`: Grafana login username
- `GRAFANA_ADMIN_PASSWORD`: optional local convenience alias for the Grafana bootstrap password
- `VAULT_IMAGE`: Vault image tag, defaults to `hashicorp/vault:1.17`
- `VAULT_DEV_ROOT_TOKEN_ID`: local-dev-only Vault bootstrap token
- `VAULT_BOOTSTRAP_SOCIAL_DB_PASSWORD`, `VAULT_BOOTSTRAP_MINIO_ROOT_PASSWORD`, `VAULT_BOOTSTRAP_GRAFANA_ADMIN_PASSWORD`: optional bootstrap secrets that `vault-init` writes into Vault on first run
- `VAULT_SOCIAL_DB_SECRET_PATH`, `VAULT_MINIO_SECRET_PATH`, `VAULT_GRAFANA_SECRET_PATH`: Vault KV paths used for the stack-managed runtime secrets
- `VAULT_GOOGLE_JWKS_URL`, `VAULT_APPLE_JWKS_URL`, `VAULT_X_JWKS_URL`: JWKS sources written into Vault
- `BROKER_JWT_*`: broker JWT validation toggles, provider enablement, and cache settings
- `BROKER_JWT_GOOGLE_AUDIENCE`: optional Google audience pin for broker JWT validation; set this to the same value as `GOOGLE_OAUTH_CLIENT_ID`
- `BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE`: optional Google audience pin used by the social REST APIs
- `BACKOFFICE_AI_ENABLED`, `BACKOFFICE_AI_API_KEY`, `BACKOFFICE_AI_MODEL`, `BACKOFFICE_AI_ASSISTANT_NAME`: shared AI assistant settings for the backoffice
- `SOCIAL_DB_IMAGE`, `SOCIAL_DB_CONTAINER_NAME`, `SOCIAL_DB_NAME`, `SOCIAL_DB_USER`, `SOCIAL_DB_VOLUME_NAME`: social database image, identity, and active persistent volume name
- `SOCIAL_DB_DEV_*`: isolated development database, container, and volume names used by `./scripts/up-dev.sh`
- `SOCIAL_DB_SEED_*`: development seed controls; set `SOCIAL_DB_SEED_FORCE=true` to rebuild the sample dataset
- The implementation scripts are grouped by concern under [`scripts/README.md`](./scripts/README.md); the familiar top-level commands in `./scripts/*.sh` remain stable wrappers.

Security drill:

- Run `./scripts/security-drill.sh` to execute live negative-auth probes against the broker.
- If the drill detects an auth bypass, it auto-applies the obvious mitigations, rebuilds the broker, and re-tests.
- If the stack still accepts the malicious probes after mitigation, the script stops `broker` and `caddy` to contain exposure.

Social media smoke test:

- Run `./scripts/social-media-smoke.sh` with `SOCIAL_BEARER_TOKEN` set to a real Google ID token from the running app session.
- The script exercises `me`, media upload, post creation, feed hydration, media download, and database attachment checks through Caddy.
- The script creates one smoke-test post and does not delete it.

Monitoring:

- Grafana is provisioned with Prometheus and Loki data sources on first start.
- `Video Chat Stack Overview` shows Spring metrics plus blackbox health probes for the local services.
- `Video Chat Service Logs` aggregates Docker container logs from the compose project.
- `./scripts/check.sh` prints the current Prometheus targets, Loki readiness, and Grafana dashboard inventory.
- `./scripts/monitoring-smoke.sh` performs a stricter end-to-end monitoring verification, including a fresh backoffice log event that must appear in both Prometheus and Loki.
- `./scripts/cleanup.sh --rotate-secrets --rebuild` resets the full Docker state, rotates local bootstrap secrets in `.env`, and rebuilds the stack from a clean slate.

## Google OAuth

The frontend now uses authorization code flow with PKCE. You still need a real Google OAuth web client ID for the app origin.

Obtain a Google web client ID in Google Cloud Console:

1. Open Google Cloud Console and select your project.
2. Go to `Google Auth Platform > Branding` and complete the consent-screen basics.
3. Go to `Google Auth Platform > Audience` and choose `External` unless this is only for your Workspace org.
4. Add your Google account as a test user if the app is still in testing.
5. Go to `Google Auth Platform > Clients`.
6. Create a new client of type `Web application`.
7. Add the HTTPS origin served by this stack as an authorized JavaScript origin.
8. Add the exact same value as an authorized redirect URI because the frontend uses `window.location.origin` as its callback.

For this stack, those values are usually:

- Authorized JavaScript origin: `https://<VIDEOCHAT_HOST>`
- Authorized redirect URI: `https://<VIDEOCHAT_HOST>`

Set it manually in `.env`:

```bash
GOOGLE_OAUTH_CLIENT_ID=<your-google-web-client-id>
docker compose up -d --build frontend
```

To enable the shared AI assistant in the stack, also set:

```bash
VIDEOCHAT_AI_AGENT_ENABLED=true
BACKOFFICE_AI_ENABLED=true
BACKOFFICE_AI_API_KEY=<your-openai-api-key>
```

Or use the helper script:

```bash
./scripts/configure-google-oauth.sh <your-google-web-client-id>
```

Your Google client must allow the HTTPS origin served by Caddy, for example `https://<VIDEOCHAT_HOST>`.

## Development mode

The stack supports a frontend development mode that keeps the same Docker deployment but exposes extra OAuth setup diagnostics on the login page.

Start it with:

```bash
./scripts/up-dev.sh
```

This runs the stack with `VIDEOCHAT_APP_MODE=development` for that compose invocation. It also switches the social database to an isolated dev database and volume, then seeds 15 sample profiles with follows, private-profile grants, posts, and reactions. If the social schema does not exist yet, the seed job applies the SQL migrations before loading the sample dataset. It does not overwrite your `.env` file.

To rebuild the dev sample data:

```bash
SOCIAL_DB_SEED_FORCE=true ./scripts/up-dev.sh
```

To replace the seeded dataset with a production backup inside the isolated dev database:

```bash
./scripts/reload-prod-social-db.sh ./backups/social-db-<timestamp>.dump
```

## Vault-backed runtime secrets

The stack starts a local Vault dev server and a one-shot `vault-init` job. That job:

- writes provider JWKS documents into Vault before the broker starts
- bootstraps the social database password, MinIO root password, and Grafana admin password into Vault
- renders runtime secret files into the persistent `vault-runtime` volume so the containers can start without keeping those secrets in `.env`
- mints a scoped broker token that can read only the configured JWT provider paths

Provider bootstrap defaults:

- Google defaults to `https://www.googleapis.com/oauth2/v3/certs`
- Apple defaults to `https://appleid.apple.com/auth/keys`
- X uses `VAULT_X_JWKS_URL` when set, or a local `vault/secrets/x-jwks.json` file if present

Any provider can be overridden by placing `google-jwks.json`, `apple-jwks.json`, or `x-jwks.json` in `vault/secrets/`. Local files win over remote URLs.

Runtime secret bootstrap precedence is:

- explicit `VAULT_BOOTSTRAP_*` values from `.env`
- `GRAFANA_ADMIN_PASSWORD` for Grafana only, when the Vault-specific bootstrap variable is empty
- matching files in `vault/secrets/`
- persisted values already present in the `vault-runtime` volume
- a newly generated random secret

If you let Grafana generate its password, you can read the current value with:

```bash
docker compose exec -T grafana sh -lc 'cat /vault/runtime/grafana-admin-password'
```

## Operations guide

Detailed install, configuration, monitoring, and troubleshooting instructions live in [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md).

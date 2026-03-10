# Video Chat Docker Stack

Docker Compose stack for the Angular frontend, RSocket broker, Spring Boot backoffice, HashiCorp Vault, and Caddy reverse proxy with locally trusted HTTPS.

## Included services

- Angular frontend
- RSocket broker
- Backoffice service
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
- Vault API: `http://127.0.0.1:${VAULT_HOST_PORT:-8200}`
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
- `CADDY_IMAGE`: Caddy image tag, defaults to `caddy:2.10.2`
- `CADDY_LOCAL_CA_FILENAME`: exported local CA certificate filename
- `VAULT_IMAGE`: Vault image tag, defaults to `hashicorp/vault:1.17`
- `VAULT_DEV_ROOT_TOKEN_ID`: local Vault token shared by the bootstrap job and broker
- `VAULT_GOOGLE_JWKS_URL`, `VAULT_APPLE_JWKS_URL`, `VAULT_X_JWKS_URL`: JWKS sources written into Vault
- `BROKER_JWT_*`: broker JWT validation toggles, provider enablement, and cache settings
- `BROKER_JWT_GOOGLE_AUDIENCE`: optional Google audience pin for broker JWT validation; set this to the same value as `GOOGLE_OAUTH_CLIENT_ID`
- `BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE`: optional Google audience pin used by the social REST APIs
- `SOCIAL_DB_*`: social database image, credentials, and active persistent volume name
- `SOCIAL_DB_DEV_*`: isolated development database, container, and volume names used by `./scripts/up-dev.sh`
- `SOCIAL_DB_SEED_*`: development seed controls; set `SOCIAL_DB_SEED_FORCE=true` to rebuild the sample dataset

Security drill:

- Run `./scripts/security-drill.sh` to execute live negative-auth probes against the broker.
- If the drill detects an auth bypass, it auto-applies the obvious mitigations, rebuilds the broker, and re-tests.
- If the stack still accepts the malicious probes after mitigation, the script stops `broker` and `caddy` to contain exposure.

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

## Vault-backed JWT validation

The stack starts a local Vault dev server and a one-shot `vault-init` job. That job writes provider JWKS documents into Vault before the broker starts:

- Google defaults to `https://www.googleapis.com/oauth2/v3/certs`
- Apple defaults to `https://appleid.apple.com/auth/keys`
- X uses `VAULT_X_JWKS_URL` when set, or a local `vault/secrets/x-jwks.json` file if present

Any provider can be overridden by placing `google-jwks.json`, `apple-jwks.json`, or `x-jwks.json` in `vault/secrets/`. Local files win over remote URLs.

## Operations guide

Detailed install, configuration, monitoring, and troubleshooting instructions live in [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md).

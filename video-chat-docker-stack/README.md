# Video Chat Docker Stack

Docker Compose stack for the Angular frontend, RSocket broker, Spring Boot backoffice, and Caddy reverse proxy with locally trusted HTTPS.

## Included services

- Angular frontend
- RSocket broker
- Backoffice service
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
- Local CA download: `http://<VIDEOCHAT_HOST>/local-ca.crt`

## Common operations

Bring the stack up:

```bash
./scripts/up.sh
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
- `CADDY_IMAGE`: Caddy image tag, defaults to `caddy:2.10.2`
- `CADDY_LOCAL_CA_FILENAME`: exported local CA certificate filename

## Operations guide

Detailed install, configuration, monitoring, and troubleshooting instructions live in [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md).

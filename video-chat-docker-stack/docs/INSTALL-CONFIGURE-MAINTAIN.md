# Docker Stack Install, Configure, and Maintain Guide

## 1. Purpose

This repository packages the full local deployment stack for the video chat system:

- Angular frontend
- Spring Boot RSocket broker
- Spring Boot backoffice
- Caddy reverse proxy with locally trusted HTTPS

The stack is intended for local development, LAN testing, and device validation on phones or tablets that need HTTPS and WSS.

## 2. Supported stack

- Docker Compose v2
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

Validate the stack before startup:

```bash
./scripts/validate.sh
```

Start everything:

```bash
./scripts/up.sh
```

Stop everything:

```bash
./scripts/down.sh
```

## 5. Configuration reference

### 5.1 `.env`

Key variables:

- `VIDEOCHAT_HOST`: host or LAN IP used by Caddy certificate and redirects
- `CADDY_IMAGE`: Caddy image tag, default `caddy:2.10.2`
- `FRONTEND_CONTAINER_NAME`: frontend container name
- `BROKER_CONTAINER_NAME`: broker container name
- `BACKOFFICE_CONTAINER_NAME`: backoffice container name
- `CADDY_CONTAINER_NAME`: Caddy container name
- `CADDY_LOCAL_CA_FILENAME`: exported local CA filename

### 5.2 `docker-compose.yml`

Responsibilities:

- builds the frontend, broker, and backoffice from sibling repos
- attaches all services to the shared `videochat-network`
- waits for broker and backoffice health checks before starting Caddy
- mounts the Caddy config and exported local CA certificate

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
- CA download: `http://<VIDEOCHAT_HOST>/local-ca.crt`

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

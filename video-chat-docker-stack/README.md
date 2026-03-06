# Video Chat Docker Stack (HTTPS + WSS)

This stack starts all required components with one command:

- Angular frontend
- RSocket broker
- Backoffice service
- Caddy reverse proxy with local HTTPS certificate (`tls internal`)

## Ports

- `https://<LAN_IP>` (main app)
- `http://<LAN_IP>` redirects to HTTPS

The frontend uses:

- `wss://<LAN_IP>/rsocket` (proxied to broker)

## Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)

## Start everything

```bash
cd ${REPO_ROOT}/video-chat-docker-stack
cp .env.example .env
# optional: set your LAN IP so cert/SNI matches on other devices
# VIDEOCHAT_HOST=192.168.1.100
./scripts/up.sh
```

## Restart after config changes

- If you changed `Caddyfile` or `VIDEOCHAT_HOST`: recreate only caddy

```bash
cd ${REPO_ROOT}/video-chat-docker-stack
docker compose up -d --force-recreate caddy
```

- If you changed app code (frontend/broker/backoffice): rebuild stack

```bash
cd ${REPO_ROOT}/video-chat-docker-stack
docker compose up -d --build
```

## Trust local CA on iPad

Caddy creates its own local CA. Export it:

```bash
cd ${REPO_ROOT}/video-chat-docker-stack
./scripts/export-caddy-root-ca.sh
```

This creates:

- `caddy-local-root-ca.crt`

Install this certificate on iPad and enable trust:

1. Install profile/certificate
2. `Settings > General > About > Certificate Trust Settings`
3. Enable full trust for this CA

Then open:

- `https://<LAN_IP>`

## Download cert via LAN URL

After export + caddy restart, the certificate is downloadable directly from your host:

- `http://<LAN_IP>/local-ca.crt`
- `https://<LAN_IP>/local-ca.crt`

Example:

- `http://<IP-ADDRESS>/local-ca.crt`

## Stop stack

```bash
cd ${REPO_ROOT}/video-chat-docker-stack
./scripts/down.sh
```

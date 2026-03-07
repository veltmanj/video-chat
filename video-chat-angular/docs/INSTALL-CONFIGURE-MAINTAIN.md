# PulseRoom Live Install, Configure, Maintain, and Troubleshoot Guide

## 1. Scope

This guide covers:

- local installation and development
- frontend configuration
- Docker stack integration with the broker, backoffice, and Caddy proxy
- validation commands
- monitoring and debugging commands
- common troubleshooting flows

## 2. Repository Layout

- Frontend app: `${REPO_ROOT}/video-chat-angular`
- Docker stack: `${REPO_ROOT}/video-chat-docker-stack`
- Broker service: `${REPO_ROOT}/video-chat-rsocket-broker`
- Backoffice service: `${REPO_ROOT}/video-chat-backoffice`

Assume `${REPO_ROOT}` is the directory containing the sibling repositories.

## 3. Prerequisites

Required:

- Node.js `24.14.0`
- npm `11.9.0` or newer
- modern Chromium-based browser for local testing

Optional:

- Docker Desktop or Docker Engine with Compose v2
- `jq` for nicer JSON health output

Quick checks:

```bash
node -v
npm -v
docker --version
docker compose version
```

Expected frontend toolchain check:

```bash
cd "${REPO_ROOT}/video-chat-angular"
./node_modules/.bin/ng version
```

## 4. Frontend Installation

Install dependencies:

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm install
```

Run the local app:

```bash
npm start
```

Open:

- `http://localhost:4200`

Quality gates:

```bash
npm run test:ci
npm run build
npm audit
```

Dependency inspection:

```bash
npm outdated
npm ls --depth=0
```

## 5. Frontend Configuration

Primary config source:

- [environment.shared.ts](../src/environments/environment.shared.ts)

What it does:

- builds a same-origin broker URL as `/rsocket`
- adds local fallback broker URLs for localhost development
- exposes `backofficeRoute`

Frontend environment files:

- [environment.ts](../src/environments/environment.ts)
- [environment.development.ts](../src/environments/environment.development.ts)

Runtime operator controls:

- `Display name`
- `Room ID`
- `Broker URL(s)`

Broker URL input rules:

- use `wss://host/rsocket` when the page is loaded over HTTPS
- use `/rsocket` when the reverse proxy is handling same-origin routing
- use `ws://localhost:9898/rsocket` or `ws://localhost:9899/rsocket` only for local HTTP development

## 6. Local Development Workflow

Start the frontend:

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm start
```

Useful checks while developing:

```bash
lsof -nP -iTCP:4200 -sTCP:LISTEN
curl -sI http://localhost:4200
```

Watch mode build:

```bash
npm run watch
```

## 7. Docker Stack Workflow

The Docker stack exposes:

- `frontend`
- `broker`
- `backoffice`
- `caddy`

Compose file:

- `${REPO_ROOT}/video-chat-docker-stack/docker-compose.yml`

Stack environment:

- `${REPO_ROOT}/video-chat-docker-stack/.env`

Current host setting example:

- `VIDEOCHAT_HOST=<VIDEOCHAT_HOST>`

Start the full stack:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
docker compose up -d --build
```

Check service state:

```bash
docker compose ps
docker compose images
```

Stop the stack:

```bash
docker compose down
```

Restart only Caddy after proxy or host changes:

```bash
docker compose up -d --force-recreate caddy
```

Rebuild only the frontend:

```bash
docker compose up -d --build frontend
```

## 8. Health and Validation Commands

### Frontend

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm run test:ci
npm run build
```

### Broker health

```bash
curl -fsS http://127.0.0.1:9898/actuator/health
curl -fsS http://127.0.0.1:9898/actuator/health | jq
```

### Backoffice health

```bash
curl -fsS http://127.0.0.1:7901/actuator/health
curl -fsS http://127.0.0.1:7901/actuator/health | jq
```

### Public HTTP/HTTPS checks through Caddy

```bash
curl -sSI "http://<VIDEOCHAT_HOST>/"
curl -skI "https://<VIDEOCHAT_HOST>/"
curl -sk --http1.1 "https://<VIDEOCHAT_HOST>/"
```

### Certificate download checks

```bash
curl -sSI "http://<VIDEOCHAT_HOST>/local-ca.crt"
curl -skI "https://<VIDEOCHAT_HOST>/local-ca.crt"
```

### Port listeners

```bash
lsof -nP -iTCP:4200 -sTCP:LISTEN
lsof -nP -iTCP:9898 -sTCP:LISTEN
lsof -nP -iTCP:7901 -sTCP:LISTEN
lsof -nP -iTCP:80 -sTCP:LISTEN
lsof -nP -iTCP:443 -sTCP:LISTEN
```

## 9. Monitoring Commands

Tail all Docker logs:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
docker compose logs -f
```

Tail selected services:

```bash
docker compose logs -f frontend
docker compose logs -f broker
docker compose logs -f backoffice
docker compose logs -f caddy
docker compose logs -f broker backoffice caddy
```

Container resource usage:

```bash
docker stats
```

Inspect container health:

```bash
docker inspect videochat-broker --format '{{json .State.Health }}'
docker inspect videochat-backoffice --format '{{json .State.Health }}'
docker inspect videochat-caddy --format '{{json .State.Status }}'
```

Inspect container networking:

```bash
docker inspect videochat-broker --format '{{json .NetworkSettings.Networks }}'
docker inspect videochat-caddy --format '{{json .NetworkSettings.Networks }}'
```

## 10. Debugging Commands

### Frontend dependency and lockfile checks

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm ls --depth=0
npm audit
rg -n '"deprecated"' package-lock.json
```

### Angular and TypeScript validation

```bash
./node_modules/.bin/ng version
npm run build
npm run test:ci
```

### RSocket and proxy-path debugging

Check whether the frontend should target same-origin `/rsocket`:

```bash
sed -n '1,220p' src/environments/environment.shared.ts
```

Check the reverse proxy config:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
sed -n '1,240p' Caddyfile
```

Check that the public app root responds:

```bash
curl -sk --http1.1 --max-time 10 "https://<VIDEOCHAT_HOST>/"
```

Check the broker actuator directly:

```bash
curl -fsS --max-time 10 http://127.0.0.1:9898/actuator/health
```

### Browser-side checks

Use browser devtools:

- `Network` tab for `/rsocket` upgrade attempts
- `Console` for camera permission errors, WebRTC errors, and websocket failures
- `Application` or certificate UI for HTTPS trust problems

## 11. Maintenance Tasks

### Update frontend dependencies

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm outdated
npm install
npm audit
npm run test:ci
npm run build
```

### Update Docker stack after code changes

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 frontend broker backoffice caddy
```

### Verify Node version after shell updates

```bash
node -v
npm -v
```

Angular 21-supported Node versions should stay in the supported range. This repo is currently validated on Node `24.14.0`.

## 12. Troubleshooting

### Symptom: Angular warns that Node is unsupported

Check:

```bash
node -v
./node_modules/.bin/ng version
```

Fix:

- switch to the configured `nvm` default version
- open a new terminal or run `source ~/.zshrc`

### Symptom: Frontend loads but cannot connect to broker

Check:

```bash
curl -fsS http://127.0.0.1:9898/actuator/health
docker compose logs --tail=100 broker
docker compose logs --tail=100 caddy
```

Fix:

- ensure broker is healthy
- ensure Caddy routes `/rsocket` to the broker
- use `wss://...` or `/rsocket` when browsing over HTTPS

### Symptom: Camera list is empty

Check:

- browser camera permissions
- whether the page is opened over HTTPS or localhost
- whether another app is holding the camera

Relevant frontend checks:

```bash
npm run test:ci
```

Fix:

- grant browser camera permission
- move to HTTPS if using a LAN device
- close competing camera apps

### Symptom: HTTPS works but iPad/browser rejects the certificate

Check:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
./scripts/export-caddy-root-ca.sh
curl -sSI "http://<VIDEOCHAT_HOST>/local-ca.crt"
```

Fix:

- install `caddy-local-root-ca.crt`
- enable full trust in device certificate settings

### Symptom: Tests pass but build fails

Check:

```bash
npm run test:ci
npm run build
```

Likely causes:

- unsupported Node version
- browser bundle incompatibility from a dependency
- Angular budget warnings turning into errors after stylesheet growth

### Symptom: UI works locally but not through Docker/LAN

Check:

```bash
docker compose ps
docker compose logs --tail=100 frontend broker backoffice caddy
curl -skI "https://<VIDEOCHAT_HOST>/"
curl -sk --http1.1 "https://<VIDEOCHAT_HOST>/"
```

Fix:

- confirm `VIDEOCHAT_HOST` matches the LAN IP or hostname you are using
- recreate Caddy after host changes
- verify that `/rsocket` is reachable through the proxy path

## 13. Recommended Routine

Before committing:

```bash
cd "${REPO_ROOT}/video-chat-angular"
npm audit
npm run test:ci
npm run build
```

Before a LAN/demo session:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
docker compose up -d --build
docker compose ps
docker compose logs --tail=50 broker backoffice caddy
curl -skI "https://<VIDEOCHAT_HOST>/"
```

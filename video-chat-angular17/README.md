# PulseRoom Live (Angular 17 + RSocket + WebRTC)

Moderne Angular 17 frontend voor multi-webcam videochat met signaling via RSocket over WebSockets en media-uitwisseling via WebRTC mesh.

## Features

- Meerdere webcams per deelnemer publiceren.
- Room join/leave, chat en camera events via RSocket.
- WebRTC peer mesh: SDP/ICE signaling via `WEBRTC_SIGNAL` events.
- Broker failover op basis van meerdere broker URL's.
- UI geïnspireerd op live streaming platforms (stage + chat + control panel).

## Configuratie

Pas broker endpoints aan in:

- `src/environments/environment.ts`
- `src/environments/environment.development.ts`

Voorbeeld:

- `wss://<LAN_IP>/rsocket` (aanbevolen bij HTTPS/Caddy)
- `ws://localhost:9898/rsocket` (alleen lokale HTTP dev)

Let op:

- Gebruik bij een HTTPS pagina altijd `wss://...` broker endpoints.
- Directe poorten zoals `:9898`/`:9899` werken niet achter de Docker Caddy setup, gebruik daar `.../rsocket` via dezelfde host.

## Starten

```bash
npm install
npm start
```

Open `http://localhost:4200`.

## HTTPS + WSS op LAN (iPad)

Gebruik de concrete local reverse proxy setup in:

- `infra/local-https/README.md`

Daarmee draai je de app op:

- `https://<LAN_IP>:8443`
- `wss://<LAN_IP>:8443/rsocket` (naar broker)

## Alles in Docker (één stack)

Zie:

- `${REPO_ROOT}/video-chat-docker-stack/README.md`

## Build

```bash
npm run build
```

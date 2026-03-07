# PulseRoom Live

Angular 21 frontend for a multi-camera room experience with:

- RSocket over WebSockets for room events and chat
- WebRTC mesh for media exchange
- Multiple broker URL failover
- A single-room live control surface with camera publishing and chat

## Prerequisites

- Node.js `24.14.0` or newer in the supported Angular 21 range
- npm `11.x`

## Quick Start

```bash
npm install
npm start
```

Open `http://localhost:4200`.

## Key Commands

```bash
npm start
npm run build
npm run test:ci
npm audit
```

## Configuration

Frontend broker URL defaults are built in:

- [src/environments/environment.shared.ts](src/environments/environment.shared.ts)
- [src/environments/environment.ts](src/environments/environment.ts)
- [src/environments/environment.development.ts](src/environments/environment.development.ts)

At runtime the UI also allows broker endpoint overrides in the room form.

## Operations Guide

Use the detailed setup and maintenance guide here:

- [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md)

## Related Repos

- Docker stack: `../video-chat-docker-stack`
- Broker service: `../video-chat-rsocket-broker`
- Backoffice service: `../video-chat-backoffice`

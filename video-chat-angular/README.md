# PulseRoom Live

Angular 21 frontend for a multi-camera room experience with:

- RSocket over WebSockets for room events and chat
- WebRTC mesh for media exchange
- Mention-based AI copilot replies through a shared backoffice AI endpoint
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

To enable the shared AI copilot with the Spring backoffice running locally:

```bash
cd ../video-chat-backoffice
BACKOFFICE_AI_ENABLED=true BACKOFFICE_AI_API_KEY=your_key_here mvn spring-boot:run
cd ../video-chat-angular
npm start
```

Then set `aiAgentEnabled: true` in `src/assets/runtime-config.js` or via the container runtime env vars below, and mention `@pulse` in chat. The frontend now publishes the AI reply back into the broker as an `AI_MESSAGE`, so everyone in the room sees it.

If you only want a browser-local fallback without the shared backoffice, keep using:

```bash
export OPENAI_API_KEY=your_key_here
npm run start:agent
```

and override `aiAgentEndpoint` to `/api/ai-agent`.

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

Runtime config keys:

- `aiAgentEnabled`
- `aiAgentEndpoint`
- `aiAgentName`
- `aiAgentMention`

Container env vars written into runtime config:

- `VIDEOCHAT_AI_AGENT_ENABLED`
- `VIDEOCHAT_AI_AGENT_ENDPOINT`
- `VIDEOCHAT_AI_AGENT_NAME`
- `VIDEOCHAT_AI_AGENT_MENTION`

## Operations Guide

Use the detailed setup and maintenance guide here:

- [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md)

## Related Repos

- Docker stack: `../video-chat-docker-stack`
- Broker service: `../video-chat-rsocket-broker`
- Backoffice service: `../video-chat-backoffice`

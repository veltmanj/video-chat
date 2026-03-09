# Video Chat

Monorepo for a browser-based video chat stack built around:

- Angular frontend
- Spring Boot RSocket broker
- Spring Boot backoffice service
- Docker Compose deployment with Caddy HTTPS termination

## Repository layout

- `video-chat-angular`: Angular frontend for chat, room events, and WebRTC media
- `video-chat-rsocket-broker`: RSocket broker for room event publish/subscribe
- `video-chat-backoffice`: event ingest service with REST inspection endpoints
- `video-chat-docker-stack`: local deployment stack for the full system

## Quick start

Run the full stack with Docker:

```bash
cd video-chat-docker-stack
cp .env.example .env
./scripts/validate.sh
./scripts/up.sh
```

Frontend-only development:

```bash
cd video-chat-angular
npm install
npm start
```

## Main entry points

- Stack operations: [video-chat-docker-stack/README.md](video-chat-docker-stack/README.md)
- Frontend: [video-chat-angular/README.md](video-chat-angular/README.md)
- Broker: [video-chat-rsocket-broker/README.md](video-chat-rsocket-broker/README.md)
- Backoffice: [video-chat-backoffice/README.md](video-chat-backoffice/README.md)

## Legal

- License selection and notes: [LICENSING.md](LICENSING.md)
- License text: [LICENSE](LICENSE)
- Terms of Service draft: [TERMS_OF_SERVICE.md](TERMS_OF_SERVICE.md)
- Acceptable Use Policy draft: [ACCEPTABLE_USE_POLICY.md](ACCEPTABLE_USE_POLICY.md)
- Cookie Policy draft: [COOKIE_POLICY.md](COOKIE_POLICY.md)

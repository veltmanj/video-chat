# Video Chat RSocket Broker

Spring Boot RSocket broker for the Angular video chat frontend. It accepts WebSocket-based RSocket clients, fans events out per room, and can forward the same events to one or more backoffice services.

## Runtime endpoints

- RSocket over WebSocket: `ws://localhost:9898/rsocket`
- Actuator health: `http://localhost:9898/actuator/health`

## Core responsibilities

- Accept `room.events.publish` requests from clients.
- Stream `room.events.stream` updates to subscribers per room.
- Forward broker traffic to backoffice endpoints when enabled.

## Quick start

```bash
mvn spring-boot:run
```

Useful checks:

```bash
curl -s http://localhost:9898/actuator/health
lsof -nP -iTCP:9898 -sTCP:LISTEN
```

## Configuration

Primary configuration lives in `src/main/resources/application.yml` and can be overridden with environment variables.

- `SERVER_PORT`: HTTP port, defaults to `9898`
- `BROKER_RSOCKET_MAPPING_PATH`: WebSocket path for the RSocket server, defaults to `/rsocket`
- `BROKER_BACKOFFICE_ENABLED`: enables forwarding, defaults to `true`
- `BROKER_BACKOFFICE_ROUTE`: backoffice ingest route, defaults to `backoffice.room.events.ingest`
- `BROKER_BACKOFFICE_ENDPOINT`: default single backoffice endpoint, defaults to `ws://localhost:7901/rsocket`
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: actuator exposure, defaults to `health,info`

## Operations guide

Detailed install, configuration, testing, monitoring, and troubleshooting instructions live in [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md).

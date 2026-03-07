# Video Chat Backoffice

Spring Boot backoffice service that accepts room events over RSocket and exposes a lightweight REST API for operational inspection.

## Runtime endpoints

- HTTP API: `http://localhost:7901`
- RSocket over WebSocket: `ws://localhost:7901/rsocket`
- Health check: `http://localhost:7901/actuator/health`

## Core responsibilities

- Accept `backoffice.room.events.ingest` messages from the broker.
- Keep a bounded in-memory history per room for debugging and audits.
- Expose room and event history over REST.

## Quick start

```bash
mvn spring-boot:run
```

Useful checks:

```bash
curl -s http://localhost:7901/actuator/health
curl -s http://localhost:7901/api/rooms
curl -s 'http://localhost:7901/api/rooms/main-stage/events?limit=20'
```

## Configuration

Primary configuration lives in `src/main/resources/application.yml` and can be overridden with environment variables.

- `SERVER_PORT`: HTTP port, defaults to `7901`
- `BACKOFFICE_RSOCKET_MAPPING_PATH`: RSocket WebSocket path, defaults to `/rsocket`
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: actuator exposure, defaults to `health,info`

## Operations guide

Detailed install, configuration, testing, monitoring, and troubleshooting instructions live in [docs/INSTALL-CONFIGURE-MAINTAIN.md](docs/INSTALL-CONFIGURE-MAINTAIN.md).

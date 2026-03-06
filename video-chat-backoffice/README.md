# Video Chat Backoffice Ingest (Spring Boot)

Backoffice service die room-events ontvangt via RSocket route:

- `backoffice.room.events.ingest`

En events beschikbaar maakt via REST voor inspectie.

## Starten

```bash
mvn spring-boot:run
```

Standaard:

- HTTP: `http://localhost:7901`
- RSocket WebSocket: `ws://localhost:7901/rsocket`

## API

- `GET /api/rooms` -> lijst actieve rooms
- `GET /api/rooms/{roomId}/events?limit=50` -> laatste events in room

## Broker koppeling

In broker `application.yml`:

```yaml
broker:
  backoffice:
    enabled: true
    route: backoffice.room.events.ingest
    endpoints:
      - name: backoffice-a
        url: ws://localhost:7901/rsocket
```

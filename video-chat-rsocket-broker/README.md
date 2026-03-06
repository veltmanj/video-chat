# Video Chat RSocket Broker (Spring Boot)

Spring Boot RSocket broker voor de Angular videochat-app.

## Wat doet deze service?

- Accepteert RSocket clients via **WebSocket** op `/rsocket`.
- Route `room.events.publish`: ontvangt room-events (join/chat/camera/webrtc-signal).
- Route `room.events.stream`: streamt events per `roomId` naar subscribers.
- Optioneel: forward events naar een backoffice RSocket endpoint (hash-based room routing).

## Configuratie

Bestand: `src/main/resources/application.yml`

Belangrijk:

- `spring.rsocket.server.port`: poort van de broker (`9898`)
- `spring.rsocket.server.mapping-path`: websocket path (`/rsocket`)
- `broker.backoffice.enabled`: zet op `true` voor forwarding
- `broker.backoffice.endpoints`: lijst van backoffice RSocket URLs
- `broker.backoffice.route`: backoffice route voor ingest

## Starten

```bash
mvn spring-boot:run
```

Of build + run:

```bash
mvn clean package
java -jar target/video-chat-rsocket-broker-0.0.1-SNAPSHOT.jar
```

## Frontend koppeling

Gebruik in Angular broker URL zoals:

- `ws://localhost:9898/rsocket`

De huidige Angular frontend gebruikt payloads:

- publish -> `room.events.publish` met `{ action, route, event }`
- stream -> `room.events.stream` met `{ action, route, roomId, clientId }`

Deze broker ondersteunt dat formaat direct.

## End-to-end lokaal (met backoffice)

1. Start backoffice:

```bash
cd ${REPO_ROOT}/video-chat-backoffice
mvn spring-boot:run
```

2. Start broker:

```bash
cd ${REPO_ROOT}/video-chat-rsocket-broker
mvn spring-boot:run
```

3. Controleer ingested events:

- `http://localhost:7901/api/rooms`
- `http://localhost:7901/api/rooms/main-stage/events?limit=50`

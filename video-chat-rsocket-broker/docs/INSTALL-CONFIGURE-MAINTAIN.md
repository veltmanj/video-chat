# Broker Install, Configure, and Maintain Guide

## 1. Purpose

The broker is the event fan-out layer between frontend clients and optional backoffice consumers. It accepts room events over RSocket, multiplexes them per room, and forwards the same events downstream when forwarding is enabled.

## 2. Supported stack

- Spring Boot `4.0.3`
- Java `17+`
- Maven `3.6.3+`

## 3. Prerequisites

Assume `${REPO_ROOT}` is the directory containing this repository.

Verify the local toolchain:

```bash
java -version
mvn -version
```

Expected minimums:

- Java 17 or newer
- Maven 3.6.3 or newer

## 4. Install and build

Build and verify the service:

```bash
cd "${REPO_ROOT}/video-chat-rsocket-broker"
mvn test
mvn -DskipTests package
```

Run from source:

```bash
mvn spring-boot:run
```

Run the packaged jar:

```bash
java -jar target/video-chat-rsocket-broker-0.0.1-SNAPSHOT.jar
```

## 5. Configuration reference

Default configuration lives in `src/main/resources/application.yml`.

Primary settings:

- `SERVER_ADDRESS`: bind address, default `0.0.0.0`
- `SERVER_PORT`: server port, default `9898`
- `BROKER_RSOCKET_MAPPING_PATH`: RSocket WebSocket path, default `/rsocket`
- `BROKER_BACKOFFICE_ENABLED`: enable downstream forwarding, default `true`
- `BROKER_BACKOFFICE_ROUTE`: forwarding route, default `backoffice.room.events.ingest`
- `BROKER_BACKOFFICE_ENDPOINT`: default single backoffice endpoint, default `ws://localhost:7901/rsocket`
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: actuator exposure, default `health,info`

Example local startup without forwarding:

```bash
BROKER_BACKOFFICE_ENABLED=false mvn spring-boot:run
```

Example with an alternate single backoffice endpoint:

```bash
BROKER_BACKOFFICE_ENDPOINT=ws://localhost:8901/rsocket mvn spring-boot:run
```

Example with multiple endpoints using `SPRING_APPLICATION_JSON`:

```bash
SPRING_APPLICATION_JSON='{
  "broker": {
    "backoffice": {
      "enabled": true,
      "route": "backoffice.room.events.ingest",
      "endpoints": [
        {"name": "backoffice-a", "url": "ws://localhost:7901/rsocket"},
        {"name": "backoffice-b", "url": "ws://localhost:7902/rsocket"}
      ]
    }
  }
}' mvn spring-boot:run
```

## 6. Runtime routes and endpoints

- RSocket publish route: `room.events.publish`
- RSocket stream route: `room.events.stream`
- Actuator health: `GET /actuator/health`

## 7. Test commands

Run all tests:

```bash
mvn test
```

Package without rerunning tests:

```bash
mvn -DskipTests package
```

Run focused test classes:

```bash
mvn -Dtest=RoomBrokerServiceTest test
mvn -Dtest=BrokerRSocketIntegrationTest test
```

## 8. Health and monitoring commands

Health check:

```bash
curl -s http://localhost:9898/actuator/health
```

Confirm the process is listening:

```bash
lsof -nP -iTCP:9898 -sTCP:LISTEN
```

Inspect a local WebSocket upgrade against the RSocket path:

```bash
curl -sv --http1.1   -H 'Connection: Upgrade'   -H 'Upgrade: websocket'   -H 'Sec-WebSocket-Version: 13'   -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='   -H 'Sec-WebSocket-Protocol: rsocket'   http://localhost:9898/rsocket
```

Inspect the JVM:

```bash
jps -lv
jcmd <pid> VM.flags
jcmd <pid> Thread.print
jcmd <pid> GC.heap_info
```

If you need actuator metrics during a debugging session, temporarily expose them:

```bash
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics mvn spring-boot:run
curl -s http://localhost:9898/actuator/metrics
```

Validate the downstream backoffice service if forwarding is enabled:

```bash
curl -s http://localhost:7901/actuator/health
```

## 9. Troubleshooting

Broker is up but clients cannot connect:

```bash
curl -s http://localhost:9898/actuator/health
lsof -nP -iTCP:9898 -sTCP:LISTEN
curl -sv --http1.1   -H 'Connection: Upgrade'   -H 'Upgrade: websocket'   -H 'Sec-WebSocket-Version: 13'   -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=='   -H 'Sec-WebSocket-Protocol: rsocket'   http://localhost:9898/rsocket
```

Forwarding is enabled but backoffice sees no events:

```bash
curl -s http://localhost:9898/actuator/health
curl -s http://localhost:7901/actuator/health
```

Then confirm `BROKER_BACKOFFICE_ENDPOINT` or `SPRING_APPLICATION_JSON` points to the correct backoffice RSocket URL.

Need verbose startup or wire logs:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments='--logging.level.root=DEBUG --logging.level.org.springframework.messaging.rsocket=DEBUG'
```

Build failures after a Java change:

```bash
java -version
mvn -version
mvn -e test
```

## 10. Maintenance checklist

Run this after dependency or code changes:

```bash
mvn test
mvn -DskipTests package
```

For production-like environments:

- Keep actuator exposure limited to `health,info` by default.
- Only enable extra actuator endpoints for a scoped debugging session.
- Recheck downstream backoffice connectivity after any endpoint, TLS, or proxy change.

# Backoffice Install, Configure, and Maintain Guide

## 1. Purpose

The backoffice service receives room events from the broker over RSocket and keeps a bounded in-memory event history per room. It is intended for inspection, debugging, and lightweight operational visibility.

## 2. Supported stack

- Spring Boot `4.0.3`
- Java `21+`
- Maven `3.6.3+`

## 3. Prerequisites

Assume `${REPO_ROOT}` is the directory containing this repository.

Install or verify the toolchain:

```bash
java -version
mvn -version
```

Expected minimums:

- Java 21 or newer
- Maven 3.6.3 or newer

## 4. Install and build

Clone the repository and build it:

```bash
cd "${REPO_ROOT}/video-chat-backoffice"
mvn test
mvn -DskipTests package
```

Run directly from source:

```bash
mvn spring-boot:run
```

Run the packaged jar:

```bash
java -jar target/video-chat-backoffice-0.0.1-SNAPSHOT.jar
```

## 5. Configuration reference

Default configuration lives in `src/main/resources/application.yml`.

Key settings:

- `SERVER_ADDRESS`: bind address, default `0.0.0.0`
- `SERVER_PORT`: HTTP port, default `7901`
- `BACKOFFICE_RSOCKET_MAPPING_PATH`: RSocket WebSocket path, default `/rsocket`
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: actuator endpoints to expose, default `health,info`

Example local override:

```bash
SERVER_PORT=8901 BACKOFFICE_RSOCKET_MAPPING_PATH=/internal-rsocket MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics mvn spring-boot:run
```

## 6. Runtime endpoints

- REST room list: `GET /api/rooms`
- REST room events: `GET /api/rooms/{roomId}/events?limit=50`
- Actuator health: `GET /actuator/health`
- RSocket ingest route: `backoffice.room.events.ingest`

## 7. Test commands

Run the full test suite:

```bash
mvn test
```

Rebuild without rerunning tests:

```bash
mvn -DskipTests package
```

Run a single test class:

```bash
mvn -Dtest=EventIngestServiceTest test
mvn -Dtest=BackofficeIntegrationTest test
```

## 8. Health and monitoring commands

Basic health check:

```bash
curl -s http://localhost:7901/actuator/health
```

Room inventory check:

```bash
curl -s http://localhost:7901/api/rooms
```

Inspect the latest room events:

```bash
curl -s 'http://localhost:7901/api/rooms/main-stage/events?limit=20'
```

Check whether the service is listening on the expected port:

```bash
lsof -nP -iTCP:7901 -sTCP:LISTEN
```

Inspect the Java process:

```bash
jps -lv
jcmd <pid> VM.flags
jcmd <pid> Thread.print
jcmd <pid> GC.heap_info
```

If you need actuator metrics during a debugging session, start the service with extra exposure:

```bash
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics mvn spring-boot:run
curl -s http://localhost:7901/actuator/metrics
```

## 9. Troubleshooting

No response on the health endpoint:

```bash
curl -sv http://localhost:7901/actuator/health
lsof -nP -iTCP:7901 -sTCP:LISTEN
```

Broker cannot forward events into the backoffice service:

```bash
curl -sv http://localhost:7901/actuator/health
```

Then verify that the broker points to the correct RSocket URL, for example `ws://localhost:7901/rsocket`.

RSocket path mismatch:

```bash
grep -n "mapping-path" src/main/resources/application.yml
```

Build failures after a Java upgrade:

```bash
java -version
mvn -version
mvn -e test
```

Need a more verbose startup log:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.root=DEBUG
```

## 10. Maintenance checklist

Run this after dependency or code changes:

```bash
mvn test
mvn -DskipTests package
```

Before exposing extra actuator endpoints in a shared environment:

- Confirm they are needed for the debugging task.
- Restrict access at the network or reverse-proxy layer.
- Revert exposure back to `health,info` after the incident.

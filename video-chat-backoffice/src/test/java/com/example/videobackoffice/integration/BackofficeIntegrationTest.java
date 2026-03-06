package com.example.videobackoffice.integration;

import com.example.videobackoffice.model.RoomEventMessage;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BackofficeIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${spring.rsocket.server.mapping-path:/rsocket}")
    private String mappingPath;

    @Autowired
    private RSocketRequester.Builder requesterBuilder;

    @Autowired
    private WebTestClient webTestClient;

    private RSocketRequester requester;

    @AfterEach
    void cleanUp() {
        if (requester != null) {
            requester.rsocketClient().dispose();
        }
    }

    @Test
    void shouldIngestViaRSocketAndExposeViaRestApis() {
        requester = requesterBuilder.transport(
            WebsocketClientTransport.create(backofficeUri())
        );

        RoomEventMessage event = new RoomEventMessage();
        event.setType("CHAT_MESSAGE");
        event.setRoomId("room-backoffice-int");
        event.setSenderId("sender-1");
        event.setSenderName("Sender");
        event.setPayload(Map.of("text", "hello from integration test"));

        requester.route("backoffice.room.events.ingest")
            .data(event)
            .send()
            .block();

        webTestClient.get()
            .uri("/api/rooms")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.rooms[0]").isEqualTo("room-backoffice-int");

        webTestClient.get()
            .uri("/api/rooms/room-backoffice-int/events?limit=1")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].type").isEqualTo("CHAT_MESSAGE")
            .jsonPath("$[0].senderId").isEqualTo("sender-1")
            .jsonPath("$[0].payload.text").isEqualTo("hello from integration test");
    }

    @Test
    void healthEndpointShouldBeUp() {
        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");
    }

    private URI backofficeUri() {
        String normalizedPath = mappingPath.startsWith("/") ? mappingPath : "/" + mappingPath;
        return URI.create("ws://localhost:" + port + normalizedPath);
    }
}

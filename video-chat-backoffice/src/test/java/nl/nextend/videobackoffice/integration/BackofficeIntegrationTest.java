package nl.nextend.videobackoffice.integration;

import nl.nextend.videobackoffice.model.RoomEventMessage;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackofficeIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${spring.rsocket.server.mapping-path:/rsocket}")
    private String mappingPath;

    @Autowired
    private RSocketRequester.Builder requesterBuilder;

    private RSocketRequester requester;
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();
    }

    @AfterEach
    void cleanUp() {
        if (requester != null) {
            requester.rsocketClient().dispose();
        }
    }

    @Test
    void shouldIngestViaRSocketAndExposeViaRestApis() {
        requester = requesterBuilder.transport(WebsocketClientTransport.create(backofficeUri()));

        RoomEventMessage event = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-backoffice-int",
            "sender-1",
            "Sender",
            null,
            Map.of("text", "hello from integration test")
        );

        requester.route("backoffice.room.events.ingest")
            .data(event)
            .retrieveMono(Void.class)
            .block();

        webTestClient.get()
            .uri("/api/rooms")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.rooms[0]").isEqualTo("room-backoffice-int")
            .jsonPath("$.count").isEqualTo(1);

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

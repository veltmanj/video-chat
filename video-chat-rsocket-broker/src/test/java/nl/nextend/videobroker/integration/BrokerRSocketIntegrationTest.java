package nl.nextend.videobroker.integration;

import nl.nextend.videobroker.model.RoomEventMessage;
import nl.nextend.videobroker.model.RoomPublishRequest;
import nl.nextend.videobroker.model.RoomStreamRequest;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "broker.backoffice.enabled=false",
        "broker.jwt.enabled=false",
        "management.endpoints.web.exposure.include=health,info"
    }
)
class BrokerRSocketIntegrationTest {

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
    void actuatorHealthShouldBeUp() {
        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void shouldReceivePublishedEventsOverRSocketWebsocket() {
        requester = requesterBuilder.transport(WebsocketClientTransport.create(brokerUri()));

        RoomStreamRequest streamRequest = new RoomStreamRequest(
            "SUBSCRIBE_ROOM",
            "room.events.stream",
            "room-int-1",
            "client-sub",
            "test-token"
        );
        RoomEventMessage event = new RoomEventMessage(
            "ROOM_JOINED",
            "room-int-1",
            "client-pub",
            "Publisher",
            null,
            Map.of("capability", "multi-webcam")
        );
        RoomPublishRequest publishRequest = new RoomPublishRequest(
            "ROOM_EVENT",
            "room.events.publish",
            "test-token",
            event
        );

        Flux<RoomEventMessage> stream = requester.route("room.events.stream")
            .data(streamRequest)
            .retrieveFlux(RoomEventMessage.class)
            .take(1);

        StepVerifier.create(stream)
            .then(() -> requester.route("room.events.publish")
                .data(publishRequest)
                .send()
                .block(Duration.ofSeconds(3)))
            .assertNext(received -> {
                assertThat(received.roomId()).isEqualTo("room-int-1");
                assertThat(received.type()).isEqualTo("ROOM_JOINED");
                assertThat(received.senderId()).isEqualTo("client-pub");
                assertThat(received.payload()).containsEntry("capability", "multi-webcam");
            })
            .verifyComplete();
    }

    private URI brokerUri() {
        String normalizedPath = mappingPath.startsWith("/") ? mappingPath : "/" + mappingPath;
        return URI.create("ws://localhost:" + port + normalizedPath);
    }
}

package com.example.videobroker.integration;

import com.example.videobroker.model.RoomEventMessage;
import com.example.videobroker.model.RoomPublishRequest;
import com.example.videobroker.model.RoomStreamRequest;
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
        "management.endpoints.web.exposure.include=health,info"
    }
)
@AutoConfigureWebTestClient
class BrokerRSocketIntegrationTest {

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
        requester = requesterBuilder.transport(
            WebsocketClientTransport.create(brokerUri())
        );

        RoomStreamRequest streamRequest = new RoomStreamRequest();
        streamRequest.setAction("SUBSCRIBE_ROOM");
        streamRequest.setRoute("room.events.stream");
        streamRequest.setRoomId("room-int-1");
        streamRequest.setClientId("client-sub");

        RoomEventMessage event = new RoomEventMessage();
        event.setType("ROOM_JOINED");
        event.setRoomId("room-int-1");
        event.setSenderId("client-pub");
        event.setSenderName("Publisher");
        event.setPayload(Map.of("capability", "multi-webcam"));

        RoomPublishRequest publishRequest = new RoomPublishRequest();
        publishRequest.setAction("ROOM_EVENT");
        publishRequest.setRoute("room.events.publish");
        publishRequest.setEvent(event);

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
                assertThat(received.getRoomId()).isEqualTo("room-int-1");
                assertThat(received.getType()).isEqualTo("ROOM_JOINED");
                assertThat(received.getSenderId()).isEqualTo("client-pub");
                assertThat(received.getPayload()).containsEntry("capability", "multi-webcam");
            })
            .verifyComplete();
    }

    private URI brokerUri() {
        String normalizedPath = mappingPath.startsWith("/") ? mappingPath : "/" + mappingPath;
        return URI.create("ws://localhost:" + port + normalizedPath);
    }
}

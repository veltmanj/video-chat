package nl.nextend.videobroker.controller;

import nl.nextend.videobroker.model.RoomEventMessage;
import nl.nextend.videobroker.model.RoomStreamRequest;
import nl.nextend.videobroker.observability.BrokerObservability;
import nl.nextend.videobroker.security.BrokerClientAuthService;
import nl.nextend.videobroker.service.RoomBrokerService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomEventControllerTest {

    private final RoomBrokerService brokerService = mock(RoomBrokerService.class);
    private final BrokerClientAuthService brokerClientAuthService = mock(BrokerClientAuthService.class);
    private final BrokerObservability observability = mock(BrokerObservability.class);

    private final RoomEventController controller = new RoomEventController(
        brokerService,
        brokerClientAuthService,
        observability
    );

    @Test
    void streamShouldUnregisterClientWhenSubscriptionEndsWithError() {
        RoomStreamRequest request = new RoomStreamRequest(
            "SUBSCRIBE_ROOM",
            "room.events.stream",
            "room-1",
            "client-1",
            "test-token"
        );
        RoomEventMessage joined = new RoomEventMessage(
            "ROOM_JOINED",
            "room-1",
            "client-2",
            "Remote",
            Instant.parse("2026-03-12T15:30:00Z"),
            Map.of("capabilities", "multi-webcam")
        );
        RoomEventMessage removed = new RoomEventMessage(
            "ROOM_LEFT",
            "room-1",
            "client-1",
            "Local",
            Instant.parse("2026-03-12T15:30:05Z"),
            Map.of()
        );

        doNothing().when(brokerClientAuthService).requireAuthorized("test-token");
        when(brokerService.subscribe("room-1")).thenReturn(Flux.concat(
            Flux.just(joined),
            Flux.error(new IllegalStateException("socket closed"))
        ));
        when(brokerService.unregisterClient("room-1", "client-1")).thenReturn(Optional.of(removed));

        StepVerifier.create(controller.stream(request))
            .expectNext(joined)
            .expectErrorMessage("socket closed")
            .verify();

        verify(observability).recordStreamSubscription();
        verify(observability, timeout(1000)).recordStreamTermination(SignalType.ON_ERROR);
        verify(brokerService, timeout(1000)).unregisterClient("room-1", "client-1");
    }

    @Test
    void streamShouldFilterWebrtcSignalsForOtherClients() {
        RoomStreamRequest request = new RoomStreamRequest(
            "SUBSCRIBE_ROOM",
            "room.events.stream",
            "room-1",
            "client-1",
            "test-token"
        );
        RoomEventMessage signalForOtherClient = new RoomEventMessage(
            "WEBRTC_SIGNAL",
            "room-1",
            "client-2",
            "Remote",
            Instant.parse("2026-03-12T15:31:00Z"),
            Map.of(
                "targetClientId", "client-3",
                "signal", Map.of("description", Map.of("type", "offer"))
            )
        );
        RoomEventMessage signalForCurrentClient = new RoomEventMessage(
            "WEBRTC_SIGNAL",
            "room-1",
            "client-2",
            "Remote",
            Instant.parse("2026-03-12T15:31:05Z"),
            Map.of(
                "targetClientId", "client-1",
                "signal", Map.of("description", Map.of("type", "offer"))
            )
        );

        doNothing().when(brokerClientAuthService).requireAuthorized("test-token");
        when(brokerService.subscribe("room-1")).thenReturn(Flux.just(signalForOtherClient, signalForCurrentClient));
        when(brokerService.unregisterClient("room-1", "client-1")).thenReturn(Optional.empty());

        StepVerifier.create(controller.stream(request))
            .expectNext(signalForCurrentClient)
            .verifyComplete();

        verify(brokerService, timeout(1000)).unregisterClient("room-1", "client-1");
        verify(observability, timeout(1000)).recordStreamTermination(SignalType.ON_COMPLETE);
    }
}

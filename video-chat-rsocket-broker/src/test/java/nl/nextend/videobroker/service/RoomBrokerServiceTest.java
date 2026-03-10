package nl.nextend.videobroker.service;

import nl.nextend.videobroker.model.RoomEventMessage;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoomBrokerServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-06T10:15:30Z"), ZoneOffset.UTC);

    private final BackofficeForwardingService forwardingService = mock(BackofficeForwardingService.class);
    private final RoomBrokerService roomBrokerService = new RoomBrokerService(forwardingService, FIXED_CLOCK);

    @Test
    void subscribeShouldExpireStaleParticipantsAndTheirCameras() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-03-06T10:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        RoomBrokerService expiringService = new RoomBrokerService(forwardingService, mutableClock);

        expiringService.publish(new RoomEventMessage(
            "ROOM_JOINED",
            "room-stale-1",
            "client-stale",
            "Stale Client",
            Instant.parse("2026-03-06T10:00:00Z"),
            Map.of("capabilities", "multi-webcam")
        ));
        expiringService.publish(new RoomEventMessage(
            "CAMERA_PUBLISHED",
            "room-stale-1",
            "client-stale",
            "Stale Client",
            Instant.parse("2026-03-06T10:00:05Z"),
            Map.of("feedId", "feed-stale", "label", "Stale Camera")
        ));

        now.set(Instant.parse("2026-03-06T10:00:30Z"));

        StepVerifier.create(expiringService.subscribe("room-stale-1"))
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(150))
            .thenCancel()
            .verify();
    }

    @Test
    void publishShouldEmitEventToSubscribersAndForwardToBackoffice() {
        RoomEventMessage event = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-unit-1",
            "client-a",
            "Alice",
            null,
            Map.of("text", "hello")
        );

        StepVerifier.create(roomBrokerService.subscribe("room-unit-1").take(1))
            .then(() -> roomBrokerService.publish(event))
            .assertNext(received -> {
                assertThat(received.roomId()).isEqualTo("room-unit-1");
                assertThat(received.type()).isEqualTo("CHAT_MESSAGE");
                assertThat(received.sentAt()).isEqualTo(Instant.parse("2026-03-06T10:15:30Z"));
                assertThat(received.payload()).containsEntry("text", "hello");
            })
            .verifyComplete();

        verify(forwardingService).forward(argThat(forwarded ->
            forwarded.roomId().equals("room-unit-1")
                && forwarded.type().equals("CHAT_MESSAGE")
                && forwarded.sentAt().equals(Instant.parse("2026-03-06T10:15:30Z"))
        ));
    }

    @Test
    void publishShouldIgnoreInvalidEvents() {
        RoomEventMessage missingRoom = new RoomEventMessage("CHAT_MESSAGE", null, "client-a", null, null, Map.of());

        StepVerifier.create(roomBrokerService.subscribe("room-unit-2"))
            .then(() -> roomBrokerService.publish(null))
            .then(() -> roomBrokerService.publish(missingRoom))
            .expectNoEvent(Duration.ofMillis(150))
            .thenCancel()
            .verify();

        verify(forwardingService, never()).forward(argThat(ignored -> true));
    }

    @Test
    void subscribeShouldStillReceiveAfterPreviousSubscriberDisconnected() {
        RoomEventMessage first = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-reconnect-1",
            "client-a",
            "Alice",
            Instant.parse("2026-03-06T09:00:00Z"),
            Map.of("text", "first")
        );

        StepVerifier.create(roomBrokerService.subscribe("room-reconnect-1").take(1))
            .then(() -> roomBrokerService.publish(first))
            .assertNext(received -> assertThat(received.payload()).containsEntry("text", "first"))
            .verifyComplete();

        RoomEventMessage second = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-reconnect-1",
            "client-b",
            "Bob",
            Instant.parse("2026-03-06T09:05:00Z"),
            Map.of("text", "second")
        );

        StepVerifier.create(roomBrokerService.subscribe("room-reconnect-1").take(1))
            .then(() -> roomBrokerService.publish(second))
            .assertNext(received -> {
                assertThat(received.senderId()).isEqualTo("client-b");
                assertThat(received.payload()).containsEntry("text", "second");
            })
            .verifyComplete();
    }

    @Test
    void subscribeShouldReplayCurrentParticipantsAndPublishedCamerasToLateJoiners() {
        RoomEventMessage joined = new RoomEventMessage(
            "ROOM_JOINED",
            "room-state-1",
            "client-a",
            "Alice",
            Instant.parse("2026-03-06T10:15:15Z"),
            Map.of("capabilities", "multi-webcam")
        );
        RoomEventMessage publishedCamera = new RoomEventMessage(
            "CAMERA_PUBLISHED",
            "room-state-1",
            "client-a",
            "Alice",
            Instant.parse("2026-03-06T10:15:20Z"),
            Map.of("feedId", "feed-a", "label", "Camera A")
        );

        roomBrokerService.publish(joined);
        roomBrokerService.publish(publishedCamera);

        StepVerifier.create(roomBrokerService.subscribe("room-state-1").take(2))
            .assertNext(received -> {
                assertThat(received.type()).isEqualTo("ROOM_JOINED");
                assertThat(received.senderId()).isEqualTo("client-a");
            })
            .assertNext(received -> {
                assertThat(received.type()).isEqualTo("CAMERA_PUBLISHED");
                assertThat(received.payload()).containsEntry("feedId", "feed-a");
            })
            .verifyComplete();
    }

    @Test
    void unregisterClientShouldRemoveParticipantAndPublishedCamerasFromSnapshot() {
        roomBrokerService.publish(new RoomEventMessage(
            "ROOM_JOINED",
            "room-state-2",
            "client-a",
            "Alice",
            Instant.parse("2026-03-06T10:15:15Z"),
            Map.of("capabilities", "multi-webcam")
        ));
        roomBrokerService.publish(new RoomEventMessage(
            "CAMERA_PUBLISHED",
            "room-state-2",
            "client-a",
            "Alice",
            Instant.parse("2026-03-06T10:15:20Z"),
            Map.of("feedId", "feed-a", "label", "Camera A")
        ));

        RoomEventMessage removed = roomBrokerService.unregisterClient("room-state-2", "client-a").orElseThrow();

        assertThat(removed.type()).isEqualTo("ROOM_LEFT");
        assertThat(removed.senderId()).isEqualTo("client-a");
        assertThat(removed.sentAt()).isEqualTo(Instant.parse("2026-03-06T10:15:30Z"));

        StepVerifier.create(roomBrokerService.subscribe("room-state-2"))
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(150))
            .thenCancel()
            .verify();
    }
}

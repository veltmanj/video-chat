package com.example.videobroker.service;

import com.example.videobroker.model.RoomEventMessage;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoomBrokerServiceTest {

    private final BackofficeForwardingService forwardingService = mock(BackofficeForwardingService.class);
    private final RoomBrokerService roomBrokerService = new RoomBrokerService(forwardingService);

    @Test
    void publishShouldEmitEventToSubscribersAndForwardToBackoffice() {
        RoomEventMessage event = new RoomEventMessage();
        event.setType("CHAT_MESSAGE");
        event.setRoomId("room-unit-1");
        event.setSenderId("client-a");
        event.setSenderName("Alice");
        event.setPayload(Map.of("text", "hello"));

        StepVerifier.create(roomBrokerService.subscribe("room-unit-1").take(1))
            .then(() -> roomBrokerService.publish(event))
            .assertNext(received -> {
                assertThat(received.getRoomId()).isEqualTo("room-unit-1");
                assertThat(received.getType()).isEqualTo("CHAT_MESSAGE");
                assertThat(received.getSentAt()).isNotNull();
                assertThat(received.getPayload()).containsEntry("text", "hello");
            })
            .verifyComplete();

        verify(forwardingService).forward(event);
    }

    @Test
    void publishShouldIgnoreInvalidEvents() {
        RoomEventMessage missingRoom = new RoomEventMessage();
        missingRoom.setType("CHAT_MESSAGE");

        StepVerifier.create(roomBrokerService.subscribe("room-unit-2"))
            .then(() -> roomBrokerService.publish(null))
            .then(() -> roomBrokerService.publish(missingRoom))
            .expectNoEvent(Duration.ofMillis(150))
            .thenCancel()
            .verify();

        verify(forwardingService, never()).forward(any());
    }

    @Test
    void subscribeShouldStillReceiveAfterPreviousSubscriberDisconnected() {
        RoomEventMessage first = new RoomEventMessage();
        first.setType("CHAT_MESSAGE");
        first.setRoomId("room-reconnect-1");
        first.setSenderId("client-a");
        first.setSenderName("Alice");
        first.setPayload(Map.of("text", "first"));

        StepVerifier.create(roomBrokerService.subscribe("room-reconnect-1").take(1))
            .then(() -> roomBrokerService.publish(first))
            .assertNext(received -> assertThat(received.getPayload()).containsEntry("text", "first"))
            .verifyComplete();

        RoomEventMessage second = new RoomEventMessage();
        second.setType("CHAT_MESSAGE");
        second.setRoomId("room-reconnect-1");
        second.setSenderId("client-b");
        second.setSenderName("Bob");
        second.setPayload(Map.of("text", "second"));

        StepVerifier.create(roomBrokerService.subscribe("room-reconnect-1").take(1))
            .then(() -> roomBrokerService.publish(second))
            .assertNext(received -> {
                assertThat(received.getSenderId()).isEqualTo("client-b");
                assertThat(received.getPayload()).containsEntry("text", "second");
            })
            .verifyComplete();
    }
}

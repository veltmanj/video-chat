package nl.nextend.videobackoffice.service;

import nl.nextend.videobackoffice.model.RoomEventMessage;
import nl.nextend.videobackoffice.observability.BackofficeObservability;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventIngestServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-06T10:15:30Z"), ZoneOffset.UTC);

    private final EventIngestService eventIngestService = new EventIngestService(FIXED_CLOCK, mock(BackofficeObservability.class));

    @Test
    void ingestShouldIgnoreInvalidEvents() {
        RoomEventMessage missingRoomId = new RoomEventMessage("CHAT_MESSAGE", null, "user-1", null, null, Map.of());

        eventIngestService.ingest(null);
        eventIngestService.ingest(missingRoomId);

        assertThat(eventIngestService.activeRooms()).isEmpty();
    }

    @Test
    void ingestShouldSetTimestampAndReturnLatestSortedEvents() {
        RoomEventMessage older = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-a",
            "user-1",
            "Alice",
            Instant.parse("2026-03-06T08:00:00Z"),
            Map.of("text", "older")
        );
        RoomEventMessage newer = new RoomEventMessage(
            "CHAT_MESSAGE",
            "room-a",
            "user-2",
            "Bob",
            Instant.parse("2026-03-06T09:00:00Z"),
            Map.of("text", "newer")
        );
        RoomEventMessage noTimestamp = new RoomEventMessage(
            "ROOM_JOINED",
            "room-a",
            "user-3",
            "Charlie",
            null,
            Map.of()
        );

        eventIngestService.ingest(older);
        eventIngestService.ingest(newer);
        eventIngestService.ingest(noTimestamp);

        assertThat(eventIngestService.activeRooms()).containsExactly("room-a");

        List<RoomEventMessage> latestEvents = eventIngestService.latestForRoom("room-a", 3);
        assertThat(latestEvents).hasSize(3);
        assertThat(latestEvents.get(0).sentAt()).isAfterOrEqualTo(latestEvents.get(1).sentAt());
        assertThat(latestEvents)
            .filteredOn(event -> "user-3".equals(event.senderId()))
            .singleElement()
            .extracting(RoomEventMessage::sentAt)
            .isEqualTo(Instant.parse("2026-03-06T10:15:30Z"));
    }

    @Test
    void latestForRoomShouldClampLimitToValidRange() {
        Instant baseline = Instant.parse("2026-03-06T09:00:00Z");

        for (int index = 0; index < 600; index++) {
            RoomEventMessage event = new RoomEventMessage(
                "CHAT_MESSAGE",
                "room-limit",
                "user-" + index,
                "User " + index,
                baseline.plusSeconds(index),
                Map.of()
            );
            eventIngestService.ingest(event);
        }

        assertThat(eventIngestService.latestForRoom("room-limit", 10_000)).hasSize(500);
        assertThat(eventIngestService.latestForRoom("room-limit", 0)).hasSize(1);
    }
}

package com.example.videobackoffice.service;

import com.example.videobackoffice.model.RoomEventMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventIngestServiceTest {

    private final EventIngestService eventIngestService = new EventIngestService();

    @Test
    void ingestShouldIgnoreInvalidEvents() {
        RoomEventMessage missingRoomId = new RoomEventMessage();
        missingRoomId.setType("CHAT_MESSAGE");

        eventIngestService.ingest(null);
        eventIngestService.ingest(missingRoomId);

        assertThat(eventIngestService.activeRooms()).isEmpty();
    }

    @Test
    void ingestShouldSetTimestampAndReturnLatestSortedEvents() {
        RoomEventMessage older = new RoomEventMessage();
        older.setType("CHAT_MESSAGE");
        older.setRoomId("room-a");
        older.setSenderId("user-1");
        older.setSentAt(Instant.parse("2026-03-06T08:00:00Z"));
        older.setPayload(Map.of("text", "older"));

        RoomEventMessage newer = new RoomEventMessage();
        newer.setType("CHAT_MESSAGE");
        newer.setRoomId("room-a");
        newer.setSenderId("user-2");
        newer.setSentAt(Instant.parse("2026-03-06T09:00:00Z"));
        newer.setPayload(Map.of("text", "newer"));

        RoomEventMessage noTimestamp = new RoomEventMessage();
        noTimestamp.setType("ROOM_JOINED");
        noTimestamp.setRoomId("room-a");
        noTimestamp.setSenderId("user-3");

        eventIngestService.ingest(older);
        eventIngestService.ingest(newer);
        eventIngestService.ingest(noTimestamp);

        assertThat(noTimestamp.getSentAt()).isNotNull();
        assertThat(eventIngestService.activeRooms()).containsExactly("room-a");

        List<RoomEventMessage> latestTwo = eventIngestService.latestForRoom("room-a", 2);
        assertThat(latestTwo).hasSize(2);
        assertThat(latestTwo.get(0).getSentAt()).isAfterOrEqualTo(latestTwo.get(1).getSentAt());
    }

    @Test
    void latestForRoomShouldClampLimitToValidRange() {
        for (int i = 0; i < 600; i++) {
            RoomEventMessage event = new RoomEventMessage();
            event.setType("CHAT_MESSAGE");
            event.setRoomId("room-limit");
            event.setSenderId("user-" + i);
            event.setSentAt(Instant.parse("2026-03-06T09:00:00Z").plusSeconds(i));
            eventIngestService.ingest(event);
        }

        assertThat(eventIngestService.latestForRoom("room-limit", 10000)).hasSize(500);
        assertThat(eventIngestService.latestForRoom("room-limit", 0)).hasSize(1);
    }
}

package com.example.videobroker.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable event payload exchanged between clients, broker, and backoffice.
 */
public record RoomEventMessage(
    String type,
    String roomId,
    String senderId,
    String senderName,
    Instant sentAt,
    Map<String, Object> payload
) {

    public RoomEventMessage {
        payload = payload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public boolean hasRoomId() {
        return roomId != null && !roomId.isBlank();
    }

    public RoomEventMessage withSentAt(Instant timestamp) {
        return new RoomEventMessage(type, roomId, senderId, senderName, timestamp, payload);
    }
}

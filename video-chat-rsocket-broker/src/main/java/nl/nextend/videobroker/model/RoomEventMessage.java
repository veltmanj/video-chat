package nl.nextend.videobroker.model;

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

    /**
     * Only room-scoped events can be routed through the broker.
     */
    public boolean hasRoomId() {
        return roomId != null && !roomId.isBlank();
    }

    /**
     * Returns a copy with a normalized broker timestamp while preserving the immutable payload copy.
     */
    public RoomEventMessage withSentAt(Instant timestamp) {
        return new RoomEventMessage(type, roomId, senderId, senderName, timestamp, payload);
    }
}

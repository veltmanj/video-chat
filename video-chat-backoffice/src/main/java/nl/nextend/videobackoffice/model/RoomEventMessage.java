package nl.nextend.videobackoffice.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable representation of a room event received from the broker.
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

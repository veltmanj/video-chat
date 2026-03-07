package nl.nextend.videobroker.model;

/**
 * Subscription request for a room-specific event stream.
 */
public record RoomStreamRequest(String action, String route, String roomId, String clientId) {

    public boolean hasRoomId() {
        return roomId != null && !roomId.isBlank();
    }

    public String clientLabel() {
        return clientId == null || clientId.isBlank() ? "anonymous" : clientId;
    }
}

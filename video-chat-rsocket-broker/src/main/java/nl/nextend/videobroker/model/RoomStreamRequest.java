package nl.nextend.videobroker.model;

/**
 * Subscription request for a room-specific event stream.
 */
public record RoomStreamRequest(String action, String route, String roomId, String clientId, String authToken) {

    /**
     * Stream subscriptions are keyed by room id, so blank room ids are rejected up front.
     */
    public boolean hasRoomId() {
        return roomId != null && !roomId.isBlank();
    }

    /**
     * Produces a log-safe label even when older clients do not send a client id yet.
     */
    public String clientLabel() {
        return clientId == null || clientId.isBlank() ? "anonymous" : clientId;
    }
}

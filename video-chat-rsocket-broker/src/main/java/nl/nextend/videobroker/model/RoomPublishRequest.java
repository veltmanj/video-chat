package nl.nextend.videobroker.model;

/**
 * Envelope used by frontend clients when publishing an event into a room stream.
 */
public record RoomPublishRequest(String action, String route, RoomEventMessage event) {

    /**
     * The broker accepts envelopes with optional action/route hints, but the event body itself is mandatory.
     */
    public boolean hasEvent() {
        return event != null;
    }
}

package nl.nextend.videobackoffice.model;

import java.util.List;

/**
 * REST payload describing the rooms currently tracked by the backoffice service.
 */
public record RoomListResponse(int count, List<String> rooms) {

    public RoomListResponse {
        rooms = List.copyOf(rooms);
    }
}

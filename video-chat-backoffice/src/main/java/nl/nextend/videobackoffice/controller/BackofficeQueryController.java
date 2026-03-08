package nl.nextend.videobackoffice.controller;

import nl.nextend.videobackoffice.model.RoomEventMessage;
import nl.nextend.videobackoffice.model.RoomListResponse;
import nl.nextend.videobackoffice.service.EventIngestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
/**
 * Read-only HTTP API for operators or dashboards that need to inspect tracked room activity.
 */
public class BackofficeQueryController {

    private final EventIngestService eventIngestService;

    public BackofficeQueryController(EventIngestService eventIngestService) {
        this.eventIngestService = eventIngestService;
    }

    @GetMapping("/api/rooms")
    /**
     * Returns the current set of rooms for which the backoffice still retains event history.
     */
    public RoomListResponse rooms() {
        List<String> rooms = eventIngestService.activeRooms();
        return new RoomListResponse(rooms.size(), rooms);
    }

    @GetMapping("/api/rooms/{roomId}/events")
    /**
     * Returns the most recent retained events for a room, newest first.
     */
    public List<RoomEventMessage> roomEvents(@PathVariable String roomId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return eventIngestService.latestForRoom(roomId, limit);
    }
}

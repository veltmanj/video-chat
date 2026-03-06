package com.example.videobackoffice.controller;

import com.example.videobackoffice.model.RoomEventMessage;
import com.example.videobackoffice.service.EventIngestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class BackofficeQueryController {

    private final EventIngestService eventIngestService;

    public BackofficeQueryController(EventIngestService eventIngestService) {
        this.eventIngestService = eventIngestService;
    }

    @GetMapping("/api/rooms")
    public Map<String, Object> rooms() {
        List<String> rooms = eventIngestService.activeRooms();
        return Map.of(
            "count", rooms.size(),
            "rooms", rooms
        );
    }

    @GetMapping("/api/rooms/{roomId}/events")
    public List<RoomEventMessage> roomEvents(@PathVariable String roomId,
                                             @RequestParam(defaultValue = "50") int limit) {
        return eventIngestService.latestForRoom(roomId, limit);
    }
}

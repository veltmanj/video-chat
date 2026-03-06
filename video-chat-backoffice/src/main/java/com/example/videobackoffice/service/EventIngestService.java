package com.example.videobackoffice.service;

import com.example.videobackoffice.model.RoomEventMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class EventIngestService {

    private static final int MAX_PER_ROOM = 1000;

    private final Map<String, ConcurrentLinkedDeque<RoomEventMessage>> roomEvents = new ConcurrentHashMap<>();

    public void ingest(RoomEventMessage event) {
        if (event == null || event.getRoomId() == null || event.getRoomId().isBlank()) {
            return;
        }

        if (event.getSentAt() == null) {
            event.setSentAt(Instant.now());
        }

        ConcurrentLinkedDeque<RoomEventMessage> queue = roomEvents.computeIfAbsent(event.getRoomId(), key -> new ConcurrentLinkedDeque<>());
        queue.addLast(event);

        while (queue.size() > MAX_PER_ROOM) {
            queue.pollFirst();
        }
    }

    public List<RoomEventMessage> latestForRoom(String roomId, int limit) {
        if (roomId == null || roomId.isBlank()) {
            return List.of();
        }

        ConcurrentLinkedDeque<RoomEventMessage> queue = roomEvents.get(roomId);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }

        List<RoomEventMessage> events = new ArrayList<>(queue);
        events.sort(Comparator.comparing(RoomEventMessage::getSentAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());

        int safeLimit = Math.max(1, Math.min(limit, 500));
        return events.subList(0, Math.min(events.size(), safeLimit));
    }

    public List<String> activeRooms() {
        return roomEvents.keySet().stream().sorted().toList();
    }
}

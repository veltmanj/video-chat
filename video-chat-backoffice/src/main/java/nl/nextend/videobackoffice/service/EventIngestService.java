package nl.nextend.videobackoffice.service;

import nl.nextend.videobackoffice.model.RoomEventMessage;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Stores a bounded in-memory event history per room for operational inspection.
 */
@Service
public class EventIngestService {

    /**
     * Hard cap per room so the service stays bounded even when rooms are busy or long-lived.
     */
    static final int MAX_EVENTS_PER_ROOM = 1_000;

    /**
     * Upper limit enforced on HTTP queries to avoid accidentally materializing very large responses.
     */
    static final int MAX_QUERY_LIMIT = 500;

    /**
     * Backoffice endpoints want newest events first, while storage appends in natural arrival order.
     */
    private static final Comparator<RoomEventMessage> BY_LATEST_FIRST = Comparator
        .comparing(RoomEventMessage::sentAt, Comparator.nullsLast(Comparator.naturalOrder()))
        .reversed();

    private final Clock clock;
    /**
     * Each room keeps its own deque so append/trim operations stay isolated and cheap.
     */
    private final Map<String, Deque<RoomEventMessage>> roomEvents = new ConcurrentHashMap<>();

    public EventIngestService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Accepts an event from the broker/backoffice ingest path and stores it if it is structurally usable.
     */
    public void ingest(RoomEventMessage event) {
        normalize(event).ifPresent(normalizedEvent -> appendToRoom(normalizedEvent.roomId(), normalizedEvent));
    }

    /**
     * Returns the latest events for a room, newest first, with a bounded query limit.
     */
    public List<RoomEventMessage> latestForRoom(String roomId, int limit) {
        if (!hasText(roomId)) {
            return List.of();
        }

        Deque<RoomEventMessage> events = roomEvents.get(roomId);
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        return events.stream()
            .sorted(BY_LATEST_FIRST)
            .limit(sanitizeLimit(limit))
            .toList();
    }

    /**
     * Lists the room ids that currently have retained history.
     */
    public List<String> activeRooms() {
        return roomEvents.keySet().stream().sorted().toList();
    }

    /**
     * Ensures invalid payloads are ignored and missing timestamps are filled in consistently.
     */
    private Optional<RoomEventMessage> normalize(RoomEventMessage event) {
        if (event == null || !event.hasRoomId()) {
            return Optional.empty();
        }

        if (event.sentAt() != null) {
            return Optional.of(event);
        }

        return Optional.of(event.withSentAt(Instant.now(clock)));
    }

    private void appendToRoom(String roomId, RoomEventMessage event) {
        Deque<RoomEventMessage> roomQueue = roomEvents.computeIfAbsent(roomId, ignored -> new ConcurrentLinkedDeque<>());
        roomQueue.addLast(event);
        trimToRetentionLimit(roomQueue);
    }

    private void trimToRetentionLimit(Deque<RoomEventMessage> roomQueue) {
        while (roomQueue.size() > MAX_EVENTS_PER_ROOM) {
            // Drop the oldest event first because new events are always appended at the tail.
            roomQueue.pollFirst();
        }
    }

    /**
     * Normalizes user-provided limits so queries always return at least one item and never exceed the
     * service safety cap.
     */
    private long sanitizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return 1;
        }

        return Math.min(requestedLimit, MAX_QUERY_LIMIT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package nl.nextend.videobroker.service;

import nl.nextend.videobroker.model.RoomEventMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains per-room sinks for live event fan-out and optional backoffice forwarding.
 */
@Service
public class RoomBrokerService {
    private static final Duration CLIENT_STALE_AFTER = Duration.ofSeconds(20);

    /**
     * Each room gets an independent hot sink so publishers and subscribers stay partitioned by room id.
     */
    private final Map<String, RoomChannel> roomChannels = new ConcurrentHashMap<>();
    private final BackofficeForwardingService forwardingService;
    private final Clock clock;

    public RoomBrokerService(BackofficeForwardingService forwardingService, Clock clock) {
        this.forwardingService = forwardingService;
        this.clock = clock;
    }

    /**
     * Returns the live event stream for a room.
     */
    public Flux<RoomEventMessage> subscribe(String roomId) {
        if (!hasText(roomId)) {
            return Flux.empty();
        }

        RoomChannel roomChannel = getOrCreateRoomChannel(roomId);
        expireStaleClients(roomId, roomChannel, Instant.now(clock));
        return Flux.concat(
            Flux.fromIterable(roomChannel.snapshotEvents()),
            roomChannel.sink().asFlux()
        );
    }

    /**
     * Normalizes, emits, and optionally forwards an event. Invalid payloads are ignored cleanly.
     */
    public Optional<RoomEventMessage> publish(RoomEventMessage event) {
        return normalize(event).map(normalizedEvent -> {
            emit(normalizedEvent);
            forwardingService.forward(normalizedEvent);
            return normalizedEvent;
        });
    }

    /**
     * Removes a participant and any published cameras when their live room stream ends unexpectedly.
     */
    public Optional<RoomEventMessage> unregisterClient(String roomId, String clientId) {
        if (!hasText(roomId) || !hasText(clientId)) {
            return Optional.empty();
        }

        RoomChannel roomChannel = roomChannels.get(roomId);
        if (roomChannel == null) {
            return Optional.empty();
        }

        Optional<RoomEventMessage> removedParticipant = roomChannel.unregisterClient(roomId, clientId, Instant.now(clock));
        removedParticipant.ifPresent(event -> {
            emit(event);
            forwardingService.forward(event);
        });
        return removedParticipant;
    }

    /**
     * Fills in broker-generated timestamps when clients omitted them and filters out unusable events.
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

    private void emit(RoomEventMessage event) {
        RoomChannel roomChannel = getOrCreateRoomChannel(event.roomId());
        expireStaleClients(event.roomId(), roomChannel, Instant.now(clock));
        roomChannel.record(event);
        Sinks.EmitResult emitResult = roomChannel.emit(event);
        if (emitResult.isSuccess()) {
            return;
        }

        // Only replace the sink when the current one is no longer usable. Concurrent signaling events
        // are serialized inside RoomChannel, so failures here point to a terminated sink.
        RoomChannel freshChannel = new RoomChannel(createRoomSink());
        freshChannel.record(event);
        roomChannels.put(event.roomId(), freshChannel);
        freshChannel.emit(event);
    }

    private RoomChannel getOrCreateRoomChannel(String roomId) {
        return roomChannels.computeIfAbsent(roomId, ignored -> new RoomChannel(createRoomSink()));
    }

    private void expireStaleClients(String roomId, RoomChannel roomChannel, Instant now) {
        roomChannel.expireStaleClients(roomId, now, CLIENT_STALE_AFTER).forEach(event -> {
            Sinks.EmitResult emitResult = roomChannel.emit(event);
            if (emitResult.isFailure()) {
                RoomChannel freshChannel = new RoomChannel(createRoomSink());
                freshChannel.record(event);
                roomChannels.put(roomId, freshChannel);
                freshChannel.emit(event);
            }
            forwardingService.forward(event);
        });
    }

    private Sinks.Many<RoomEventMessage> createRoomSink() {
        // Keep the sink alive when subscribers disconnect so reconnecting clients
        // do not inherit a terminated stream instance.
        return Sinks.many().multicast().onBackpressureBuffer(256, false);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class RoomChannel {
        private final Sinks.Many<RoomEventMessage> sink;
        private final Map<String, RoomEventMessage> participantsBySenderId = new LinkedHashMap<>();
        private final Map<String, RoomEventMessage> camerasByFeedKey = new LinkedHashMap<>();
        private final Map<String, Instant> lastSeenBySenderId = new LinkedHashMap<>();

        private RoomChannel(Sinks.Many<RoomEventMessage> sink) {
            this.sink = sink;
        }

        private Sinks.Many<RoomEventMessage> sink() {
            return sink;
        }

        private synchronized Sinks.EmitResult emit(RoomEventMessage event) {
            return sink.tryEmitNext(event);
        }

        private synchronized void record(RoomEventMessage event) {
            if (event == null) {
                return;
            }

            if (hasText(event.senderId()) && event.sentAt() != null) {
                lastSeenBySenderId.put(event.senderId(), event.sentAt());
            }

            if (!hasSnapshotState(event)) {
                return;
            }

            String senderId = event.senderId();
            if ("ROOM_JOINED".equals(event.type()) && hasText(senderId)) {
                participantsBySenderId.put(senderId, event);
                return;
            }

            if ("ROOM_LEFT".equals(event.type()) && hasText(senderId)) {
                participantsBySenderId.remove(senderId);
                camerasByFeedKey.entrySet().removeIf(entry -> senderId.equals(entry.getValue().senderId()));
                lastSeenBySenderId.remove(senderId);
                return;
            }

            String cameraFeedKey = cameraFeedKey(event);
            if (!hasText(cameraFeedKey)) {
                return;
            }

            if ("CAMERA_PUBLISHED".equals(event.type()) || "CAMERA_STATUS".equals(event.type())) {
                camerasByFeedKey.put(cameraFeedKey, event);
                return;
            }

            if ("CAMERA_REMOVED".equals(event.type())) {
                camerasByFeedKey.remove(cameraFeedKey);
            }
        }

        private synchronized List<RoomEventMessage> snapshotEvents() {
            List<RoomEventMessage> snapshot = new ArrayList<>(participantsBySenderId.values().size() + camerasByFeedKey.values().size());
            snapshot.addAll(participantsBySenderId.values());
            snapshot.addAll(camerasByFeedKey.values());
            snapshot.sort(Comparator
                .comparing(RoomEventMessage::sentAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(event -> event.senderId() == null ? "" : event.senderId())
                .thenComparing(event -> event.type() == null ? "" : event.type()));
            return snapshot;
        }

        private synchronized Optional<RoomEventMessage> unregisterClient(String roomId, String clientId, Instant sentAt) {
            RoomEventMessage existingParticipant = participantsBySenderId.remove(clientId);
            if (existingParticipant == null) {
                lastSeenBySenderId.remove(clientId);
                return Optional.empty();
            }

            camerasByFeedKey.entrySet().removeIf(entry -> clientId.equals(entry.getValue().senderId()));
            lastSeenBySenderId.remove(clientId);
            return Optional.of(new RoomEventMessage(
                "ROOM_LEFT",
                roomId,
                clientId,
                existingParticipant.senderName(),
                sentAt,
                Map.of()
            ));
        }

        private synchronized List<RoomEventMessage> expireStaleClients(String roomId, Instant now, Duration staleAfter) {
            List<String> staleClientIds = lastSeenBySenderId.entrySet().stream()
                .filter(entry -> isStale(entry.getValue(), now, staleAfter))
                .map(Map.Entry::getKey)
                .toList();

            if (staleClientIds.isEmpty()) {
                return List.of();
            }

            List<RoomEventMessage> expiredEvents = new ArrayList<>();
            for (String staleClientId : staleClientIds) {
                RoomEventMessage participant = participantsBySenderId.remove(staleClientId);
                camerasByFeedKey.entrySet().removeIf(entry -> staleClientId.equals(entry.getValue().senderId()));
                lastSeenBySenderId.remove(staleClientId);

                if (participant != null) {
                    expiredEvents.add(new RoomEventMessage(
                        "ROOM_LEFT",
                        roomId,
                        staleClientId,
                        participant.senderName(),
                        now,
                        Map.of("reason", "stale-client-expired")
                    ));
                }
            }

            return expiredEvents;
        }

        private boolean isStale(Instant lastSeen, Instant now, Duration staleAfter) {
            return lastSeen == null || lastSeen.plus(staleAfter).isBefore(now);
        }

        private boolean hasSnapshotState(RoomEventMessage event) {
            return event.type() != null && switch (event.type()) {
                case "ROOM_JOINED", "ROOM_LEFT", "CAMERA_PUBLISHED", "CAMERA_REMOVED", "CAMERA_STATUS" -> true;
                default -> false;
            };
        }

        private String cameraFeedKey(RoomEventMessage event) {
            Object feedId = event.payload().get("feedId");
            if (!hasText(event.senderId()) || !(feedId instanceof String feedIdValue) || !hasText(feedIdValue)) {
                return null;
            }

            return event.senderId() + "|" + feedIdValue;
        }
    }
}

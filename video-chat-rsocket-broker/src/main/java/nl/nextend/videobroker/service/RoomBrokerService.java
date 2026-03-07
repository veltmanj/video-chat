package nl.nextend.videobroker.service;

import nl.nextend.videobroker.model.RoomEventMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains per-room sinks for live event fan-out and optional backoffice forwarding.
 */
@Service
public class RoomBrokerService {

    private final Map<String, Sinks.Many<RoomEventMessage>> roomChannels = new ConcurrentHashMap<>();
    private final BackofficeForwardingService forwardingService;
    private final Clock clock;

    public RoomBrokerService(BackofficeForwardingService forwardingService, Clock clock) {
        this.forwardingService = forwardingService;
        this.clock = clock;
    }

    public Flux<RoomEventMessage> subscribe(String roomId) {
        if (!hasText(roomId)) {
            return Flux.empty();
        }

        return getOrCreateRoomSink(roomId).asFlux();
    }

    public Optional<RoomEventMessage> publish(RoomEventMessage event) {
        return normalize(event).map(normalizedEvent -> {
            emit(normalizedEvent);
            forwardingService.forward(normalizedEvent);
            return normalizedEvent;
        });
    }

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
        Sinks.Many<RoomEventMessage> sink = getOrCreateRoomSink(event.roomId());
        Sinks.EmitResult emitResult = sink.tryEmitNext(event);
        if (emitResult.isSuccess()) {
            return;
        }

        Sinks.Many<RoomEventMessage> freshSink = createRoomSink();
        roomChannels.put(event.roomId(), freshSink);
        freshSink.tryEmitNext(event);
    }

    private Sinks.Many<RoomEventMessage> getOrCreateRoomSink(String roomId) {
        return roomChannels.computeIfAbsent(roomId, ignored -> createRoomSink());
    }

    private Sinks.Many<RoomEventMessage> createRoomSink() {
        // Keep the sink alive when subscribers disconnect so reconnecting clients
        // do not inherit a terminated stream instance.
        return Sinks.many().multicast().onBackpressureBuffer(256, false);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

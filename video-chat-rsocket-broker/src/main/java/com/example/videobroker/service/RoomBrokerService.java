package com.example.videobroker.service;

import com.example.videobroker.model.RoomEventMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomBrokerService {

    private final Map<String, Sinks.Many<RoomEventMessage>> roomChannels = new ConcurrentHashMap<>();
    private final BackofficeForwardingService forwardingService;

    public RoomBrokerService(BackofficeForwardingService forwardingService) {
        this.forwardingService = forwardingService;
    }

    public Flux<RoomEventMessage> subscribe(String roomId) {
        return getOrCreateRoomSink(roomId).asFlux();
    }

    public void publish(RoomEventMessage event) {
        if (event == null || event.getRoomId() == null || event.getRoomId().isBlank()) {
            return;
        }

        if (event.getSentAt() == null) {
            event.setSentAt(Instant.now());
        }

        Sinks.Many<RoomEventMessage> sink = getOrCreateRoomSink(event.getRoomId());
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            Sinks.Many<RoomEventMessage> freshSink = createRoomSink();
            roomChannels.put(event.getRoomId(), freshSink);
            freshSink.tryEmitNext(event);
        }

        forwardingService.forward(event);
    }

    private Sinks.Many<RoomEventMessage> getOrCreateRoomSink(String roomId) {
        return roomChannels.computeIfAbsent(roomId, key -> createRoomSink());
    }

    private Sinks.Many<RoomEventMessage> createRoomSink() {
        // Keep sink alive when all subscribers disconnect, so reconnecting clients
        // do not immediately receive onComplete() from a terminated sink.
        return Sinks.many().multicast().onBackpressureBuffer(256, false);
    }
}

package com.example.videobroker.controller;

import com.example.videobroker.model.RoomEventMessage;
import com.example.videobroker.model.RoomPublishRequest;
import com.example.videobroker.model.RoomStreamRequest;
import com.example.videobroker.service.RoomBrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
public class RoomEventController {

    private static final Logger log = LoggerFactory.getLogger(RoomEventController.class);

    private final RoomBrokerService brokerService;

    public RoomEventController(RoomBrokerService brokerService) {
        this.brokerService = brokerService;
    }

    @MessageMapping("room.events.publish")
    public Mono<Void> publish(RoomPublishRequest request) {
        if (request == null || !request.hasEvent()) {
            log.warn("Publish route invoked without an event payload");
            return Mono.empty();
        }

        brokerService.publish(request.event())
            .ifPresentOrElse(
                this::logPublishedEvent,
                () -> log.warn("Publish route invoked with an invalid room event")
            );

        return Mono.empty();
    }

    @MessageMapping("room.events.stream")
    public Flux<RoomEventMessage> stream(RoomStreamRequest request) {
        if (request == null || !request.hasRoomId()) {
            log.warn("Stream route invoked without a valid roomId");
            return Flux.empty();
        }

        log.info("Stream subscribe: roomId={}, clientId={}", request.roomId(), request.clientLabel());
        return brokerService.subscribe(request.roomId())
            .doOnCancel(() -> log.info("Stream cancel: roomId={}, clientId={}", request.roomId(), request.clientLabel()))
            .doOnComplete(() -> log.info("Stream complete: roomId={}, clientId={}", request.roomId(), request.clientLabel()));
    }

    private void logPublishedEvent(RoomEventMessage event) {
        log.info("Published event type={} room={} sender={}", event.type(), event.roomId(), event.senderId());
    }
}

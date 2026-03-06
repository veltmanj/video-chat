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
        if (request != null && request.getEvent() != null) {
            RoomEventMessage event = request.getEvent();
            log.info("publish route hit: roomId={}, type={}, sender={}",
                event.getRoomId(), event.getType(), event.getSenderId());
            brokerService.publish(request.getEvent());
        } else {
            log.warn("publish route hit with empty request/event");
        }

        return Mono.empty();
    }

    @MessageMapping("room.events.stream")
    public Flux<RoomEventMessage> stream(RoomStreamRequest request) {
        if (request == null || request.getRoomId() == null || request.getRoomId().isBlank()) {
            log.warn("stream route hit with invalid request");
            return Flux.empty();
        }

        log.info("stream subscribe: roomId={}, clientId={}", request.getRoomId(), request.getClientId());
        return brokerService.subscribe(request.getRoomId())
            .doOnCancel(() -> log.info("stream cancel: roomId={}, clientId={}", request.getRoomId(), request.getClientId()))
            .doOnComplete(() -> log.info("stream complete: roomId={}, clientId={}", request.getRoomId(), request.getClientId()));
    }
}

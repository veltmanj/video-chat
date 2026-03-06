package com.example.videobackoffice.controller;

import com.example.videobackoffice.model.RoomEventMessage;
import com.example.videobackoffice.service.EventIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

@Controller
public class BackofficeIngestRSocketController {

    private static final Logger log = LoggerFactory.getLogger(BackofficeIngestRSocketController.class);

    private final EventIngestService eventIngestService;

    public BackofficeIngestRSocketController(EventIngestService eventIngestService) {
        this.eventIngestService = eventIngestService;
    }

    @MessageMapping("backoffice.room.events.ingest")
    public Mono<Void> ingest(RoomEventMessage event) {
        eventIngestService.ingest(event);
        if (event != null) {
            log.info("Ingested event type={} room={} sender={}", event.getType(), event.getRoomId(), event.getSenderId());
        }

        return Mono.empty();
    }
}

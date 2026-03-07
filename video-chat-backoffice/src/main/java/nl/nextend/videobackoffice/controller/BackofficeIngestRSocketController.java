package nl.nextend.videobackoffice.controller;

import nl.nextend.videobackoffice.model.RoomEventMessage;
import nl.nextend.videobackoffice.service.EventIngestService;
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

        if (event == null || !event.hasRoomId()) {
            log.debug("Ignoring invalid backoffice event payload");
            return Mono.empty();
        }

        log.info("Ingested event type={} room={} sender={}", event.type(), event.roomId(), event.senderId());
        return Mono.empty();
    }
}

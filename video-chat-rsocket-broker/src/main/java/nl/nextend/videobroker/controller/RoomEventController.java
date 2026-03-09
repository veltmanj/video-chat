package nl.nextend.videobroker.controller;

import nl.nextend.videobroker.model.RoomEventMessage;
import nl.nextend.videobroker.model.RoomPublishRequest;
import nl.nextend.videobroker.model.RoomStreamRequest;
import nl.nextend.videobroker.security.BrokerClientAuthService;
import nl.nextend.videobroker.service.RoomBrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
/**
 * RSocket controller that exposes the broker publish and stream routes used by frontend clients.
 */
public class RoomEventController {

    private static final Logger log = LoggerFactory.getLogger(RoomEventController.class);

    private final RoomBrokerService brokerService;
    private final BrokerClientAuthService brokerClientAuthService;

    public RoomEventController(RoomBrokerService brokerService, BrokerClientAuthService brokerClientAuthService) {
        this.brokerService = brokerService;
        this.brokerClientAuthService = brokerClientAuthService;
    }

    @MessageMapping("room.events.authorize")
    /**
     * Validates the supplied bearer token so clients fail before opening the live stream.
     */
    public Mono<Void> authorize(RoomStreamRequest request) {
        if (request == null) {
            log.warn("Authorize route invoked without a request payload");
            return Mono.empty();
        }

        brokerClientAuthService.requireAuthorized(request.authToken());
        log.info("Authorization accepted: roomId={}, clientId={}", request.roomId(), request.clientLabel());
        return Mono.empty();
    }

    @MessageMapping("room.events.publish")
    /**
     * Accepts a single room event and republishes it to the in-memory room stream.
     */
    public Mono<Void> publish(RoomPublishRequest request) {
        if (request == null || !request.hasEvent()) {
            log.warn("Publish route invoked without an event payload");
            return Mono.empty();
        }

        brokerClientAuthService.requireAuthorized(request.authToken());

        brokerService.publish(request.event())
            .ifPresentOrElse(
                this::logPublishedEvent,
                () -> log.warn("Publish route invoked with an invalid room event")
            );

        return Mono.empty();
    }

    @MessageMapping("room.events.stream")
    /**
     * Opens a live subscription for a single room.
     */
    public Flux<RoomEventMessage> stream(RoomStreamRequest request) {
        if (request == null || !request.hasRoomId()) {
            log.warn("Stream route invoked without a valid roomId");
            return Flux.empty();
        }

        brokerClientAuthService.requireAuthorized(request.authToken());

        log.info("Stream subscribe: roomId={}, clientId={}", request.roomId(), request.clientLabel());
        return brokerService.subscribe(request.roomId())
            .doOnCancel(() -> log.info("Stream cancel: roomId={}, clientId={}", request.roomId(), request.clientLabel()))
            .doOnComplete(() -> log.info("Stream complete: roomId={}, clientId={}", request.roomId(), request.clientLabel()));
    }

    private void logPublishedEvent(RoomEventMessage event) {
        log.info("Published event type={} room={} sender={}", event.type(), event.roomId(), event.senderId());
    }
}

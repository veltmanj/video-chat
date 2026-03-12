package nl.nextend.videobroker.controller;

import nl.nextend.videobroker.model.RoomEventMessage;
import nl.nextend.videobroker.model.RoomPublishRequest;
import nl.nextend.videobroker.model.RoomStreamRequest;
import nl.nextend.videobroker.observability.BrokerObservability;
import nl.nextend.videobroker.security.BrokerClientAuthService;
import nl.nextend.videobroker.service.RoomBrokerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.SignalType;

import java.util.Map;

@Controller
/**
 * RSocket controller that exposes the broker publish and stream routes used by frontend clients.
 */
public class RoomEventController {

    private static final Logger log = LoggerFactory.getLogger(RoomEventController.class);

    private final RoomBrokerService brokerService;
    private final BrokerClientAuthService brokerClientAuthService;
    private final BrokerObservability observability;

    public RoomEventController(
        RoomBrokerService brokerService,
        BrokerClientAuthService brokerClientAuthService,
        BrokerObservability observability
    ) {
        this.brokerService = brokerService;
        this.brokerClientAuthService = brokerClientAuthService;
        this.observability = observability;
    }

    @MessageMapping("room.events.authorize")
    /**
     * Validates the supplied bearer token so clients fail before opening the live stream.
     */
    public Mono<Map<String, String>> authorize(RoomStreamRequest request) {
        if (request == null) {
            log.warn("Authorize route invoked without a request payload");
            return Mono.just(Map.of("status", "ignored"));
        }

        return Mono.fromRunnable(() -> brokerClientAuthService.requireAuthorized(request.authToken()))
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(Map.of("status", "authorized"))
            .doOnNext(ignored -> {
                observability.recordAuthorization("authorized");
                log.info("Authorization accepted: roomId={}, clientId={}", request.roomId(), request.clientLabel());
            })
            .doOnError(error -> observability.recordAuthorization(resolveAuthorizationOutcome(error)));
    }

    @MessageMapping("room.events.publish")
    /**
     * Accepts a single room event and republishes it to the in-memory room stream.
     */
    public Mono<Void> publish(RoomPublishRequest request) {
        if (request == null || !request.hasEvent()) {
            observability.recordInvalidPublish();
            log.warn("Publish route invoked without an event payload");
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> brokerClientAuthService.requireAuthorized(request.authToken()))
            .subscribeOn(Schedulers.boundedElastic())
            .then(Mono.fromRunnable(() ->
                brokerService.publish(request.event())
                    .ifPresentOrElse(
                        this::logPublishedEvent,
                        () -> {
                            observability.recordInvalidPublish();
                            log.warn("Publish route invoked with an invalid room event");
                        }
                    )
            ));
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

        return Mono.fromRunnable(() -> brokerClientAuthService.requireAuthorized(request.authToken()))
            .subscribeOn(Schedulers.boundedElastic())
            .thenMany(Flux.defer(() -> {
                observability.recordStreamSubscription();
                log.info("Stream subscribe: roomId={}, clientId={}", request.roomId(), request.clientLabel());
                return brokerService.subscribe(request.roomId())
                    .filter(event -> shouldDeliverToClient(event, request.clientLabel()))
                    .doOnCancel(() -> log.info("Stream cancel: roomId={}, clientId={}", request.roomId(), request.clientLabel()))
                    .doOnComplete(() -> log.info("Stream complete: roomId={}, clientId={}", request.roomId(), request.clientLabel()))
                    .doFinally(signalType -> {
                        observability.recordStreamTermination(signalType);
                        unregisterDisconnectedClient(request, signalType);
                    });
            }));
    }

    private String resolveAuthorizationOutcome(Throwable error) {
        String message = String.valueOf(error == null ? "" : error.getMessage()).toLowerCase();
        if (message.contains("unauthorized") || message.contains("forbidden") || message.contains("access denied")) {
            return "denied";
        }
        return "error";
    }

    private void unregisterDisconnectedClient(RoomStreamRequest request, SignalType signalType) {
        if (!request.hasRoomId()) {
            return;
        }

        brokerService.unregisterClient(request.roomId(), request.clientLabel())
            .ifPresent(event -> log.info(
                "Removed disconnected client from room snapshot: roomId={}, clientId={}, signal={}",
                event.roomId(),
                event.senderId(),
                signalType
            ));
    }

    private void logPublishedEvent(RoomEventMessage event) {
        if ("WEBRTC_SIGNAL".equals(event.type())) {
            Object targetClientId = event.payload().get("targetClientId");
            Object signal = event.payload().get("signal");
            String descriptionType = signal instanceof Map<?, ?> signalMap
                && signalMap.get("description") instanceof Map<?, ?> descriptionMap
                && descriptionMap.get("type") instanceof String type
                ? type
                : (signal instanceof Map<?, ?> signalMap && signalMap.containsKey("candidate") ? "candidate" : "unknown");
            log.info(
                "Published event type={} room={} sender={} target={} signal={}",
                event.type(),
                event.roomId(),
                event.senderId(),
                targetClientId,
                descriptionType
            );
            return;
        }

        log.info("Published event type={} room={} sender={}", event.type(), event.roomId(), event.senderId());
    }

    private boolean shouldDeliverToClient(RoomEventMessage event, String clientId) {
        if (event == null || !"WEBRTC_SIGNAL".equals(event.type())) {
            return true;
        }

        Object targetClientId = event.payload().get("targetClientId");
        return targetClientId instanceof String target && target.equals(clientId);
    }
}

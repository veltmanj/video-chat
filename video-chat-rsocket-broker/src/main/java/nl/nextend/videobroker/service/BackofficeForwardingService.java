package nl.nextend.videobroker.service;

import nl.nextend.videobroker.config.BackofficeRoutingProperties;
import nl.nextend.videobroker.config.BackofficeRoutingProperties.BackofficeEndpoint;
import nl.nextend.videobroker.model.RoomEventMessage;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * Mirrors broker events to a backoffice service when forwarding is enabled.
 *
 * <p>The forwarding path is intentionally best-effort: broker fan-out to room subscribers remains the
 * primary responsibility, while backoffice delivery failures are logged and retried on the next event.
 */
public class BackofficeForwardingService {

    private static final Logger log = LoggerFactory.getLogger(BackofficeForwardingService.class);

    private final BackofficeRoutingProperties properties;
    private final RSocketRequester.Builder requesterBuilder;
    private final Map<String, RSocketRequester> requesterCache = new ConcurrentHashMap<>();

    public BackofficeForwardingService(BackofficeRoutingProperties properties, RSocketRequester.Builder requesterBuilder) {
        this.properties = properties;
        this.requesterBuilder = requesterBuilder;
    }

    /**
     * Resolves a deterministic backoffice target for the room and sends the event there.
     */
    public void forward(RoomEventMessage event) {
        resolveEndpoint(event).ifPresent(endpoint -> send(endpoint, event));
    }

    /**
     * Uses a stable hash on room id so all events for a room land on the same backoffice endpoint.
     */
    private Optional<BackofficeEndpoint> resolveEndpoint(RoomEventMessage event) {
        if (!properties.isEnabled() || event == null || !event.hasRoomId()) {
            return Optional.empty();
        }

        List<BackofficeEndpoint> endpoints = properties.usableEndpoints();
        if (endpoints.isEmpty()) {
            return Optional.empty();
        }

        int index = Math.floorMod(event.roomId().hashCode(), endpoints.size());
        return Optional.of(endpoints.get(index));
    }

    /**
     * Reuses cached requesters per endpoint to avoid reconnecting on every forwarded event.
     */
    private void send(BackofficeEndpoint endpoint, RoomEventMessage event) {
        try {
            RSocketRequester requester = requesterCache.computeIfAbsent(endpoint.getUrl(), this::createRequester);
            if (requester == null) {
                return;
            }

            requester.route(properties.getRoute())
                .data(event)
                .retrieveMono(Void.class)
                .doOnSuccess(ignored -> log.info(
                    "Forwarded event type={} room={} to {}",
                    event.type(),
                    event.roomId(),
                    endpoint.displayName()
                ))
                .doOnError(error -> handleSendFailure(endpoint, error))
                .subscribe();
        } catch (Exception error) {
            handleSendFailure(endpoint, error);
        }
    }

    private void handleSendFailure(BackofficeEndpoint endpoint, Throwable error) {
        // Force a fresh requester on the next event because the cached connection may be stale.
        requesterCache.remove(endpoint.getUrl());
        log.warn("Failed to forward event to {} ({}): {}", endpoint.displayName(), endpoint.getUrl(), error.getMessage());
    }

    /**
     * Builds an RSocket requester for a configured endpoint URL. Invalid endpoints are logged once
     * and skipped until configuration changes.
     */
    private RSocketRequester createRequester(String endpointUrl) {
        try {
            URI uri = URI.create(endpointUrl);
            ClientTransport transport = createTransport(uri);
            if (transport == null) {
                return null;
            }
            return requesterBuilder.transport(transport);
        } catch (Exception error) {
            log.warn("Unable to create RSocket requester for {}: {}", endpointUrl, error.getMessage());
            return null;
        }
    }

    /**
     * Supports websocket and raw TCP transports because local deployments often use ws/wss while
     * internal service-to-service paths may prefer TCP.
     */
    private ClientTransport createTransport(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        return switch (scheme) {
            case "ws", "wss" -> WebsocketClientTransport.create(uri);
            case "tcp" -> {
                if (uri.getHost() == null || uri.getPort() < 0) {
                    log.warn("TCP backoffice endpoint must include host and port: {}", uri);
                    yield null;
                }
                yield TcpClientTransport.create(uri.getHost(), uri.getPort());
            }
            default -> {
                log.warn("Unsupported backoffice transport scheme in {}", uri);
                yield null;
            }
        };
    }
}

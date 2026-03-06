package com.example.videobroker.service;

import com.example.videobroker.config.BackofficeRoutingProperties;
import com.example.videobroker.model.RoomEventMessage;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketRequester.Builder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BackofficeForwardingService {

    private static final Logger log = LoggerFactory.getLogger(BackofficeForwardingService.class);

    private final BackofficeRoutingProperties properties;
    private final Builder requesterBuilder;
    private final Map<String, RSocketRequester> requesterCache = new ConcurrentHashMap<>();

    public BackofficeForwardingService(BackofficeRoutingProperties properties, Builder requesterBuilder) {
        this.properties = properties;
        this.requesterBuilder = requesterBuilder;
    }

    public void forward(RoomEventMessage event) {
        if (!properties.isEnabled()) {
            return;
        }

        List<BackofficeRoutingProperties.BackofficeEndpoint> endpoints = properties.getEndpoints();
        if (endpoints == null || endpoints.isEmpty() || event.getRoomId() == null) {
            return;
        }

        BackofficeRoutingProperties.BackofficeEndpoint endpoint = pickEndpoint(event.getRoomId(), endpoints);
        if (endpoint == null || endpoint.getUrl() == null || endpoint.getUrl().isBlank()) {
            return;
        }

        try {
            RSocketRequester requester = requesterCache.computeIfAbsent(endpoint.getUrl(), this::createRequester);
            if (requester == null) {
                return;
            }

            requester
                .route(properties.getRoute())
                .data(event)
                .send()
                .doOnSuccess(v -> log.info("Forwarded event to backoffice: roomId={}, type={}, target={}",
                    event.getRoomId(), event.getType(), endpoint.getUrl()))
                .doOnError(ex -> log.warn("Forward naar {} mislukt: {}", endpoint.getUrl(), ex.getMessage()))
                .subscribe();
        } catch (Exception ex) {
            log.warn("Forward naar {} mislukt: {}", endpoint.getUrl(), ex.getMessage());
        }
    }

    private BackofficeRoutingProperties.BackofficeEndpoint pickEndpoint(String roomId,
                                                                         List<BackofficeRoutingProperties.BackofficeEndpoint> endpoints) {
        int index = Math.abs(roomId.hashCode() % endpoints.size());
        return endpoints.get(index);
    }

    private RSocketRequester createRequester(String endpointUrl) {
        URI uri = URI.create(endpointUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        try {
            ClientTransport transport;
            if ("ws".equals(scheme) || "wss".equals(scheme)) {
                transport = WebsocketClientTransport.create(uri);
            } else if ("tcp".equals(scheme)) {
                transport = TcpClientTransport.create(uri.getHost(), uri.getPort());
            } else {
                log.warn("Onbekend backoffice transport schema: {}", endpointUrl);
                return null;
            }

            return requesterBuilder.transport(transport);
        } catch (Exception ex) {
            log.warn("Kon geen RSocketRequester maken voor {}: {}", endpointUrl, ex.getMessage());
            return null;
        }
    }
}

package com.example.videobroker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for optional broker-to-backoffice forwarding.
 */
@ConfigurationProperties(prefix = "broker.backoffice")
public class BackofficeRoutingProperties {

    private static final String DEFAULT_ROUTE = "backoffice.room.events.ingest";

    private boolean enabled;
    private String route = DEFAULT_ROUTE;
    private List<BackofficeEndpoint> endpoints = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRoute() {
        return hasText(route) ? route : DEFAULT_ROUTE;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public List<BackofficeEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<BackofficeEndpoint> endpoints) {
        this.endpoints = endpoints == null ? new ArrayList<>() : new ArrayList<>(endpoints);
    }

    public List<BackofficeEndpoint> usableEndpoints() {
        return endpoints.stream().filter(BackofficeEndpoint::hasUrl).toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class BackofficeEndpoint {

        private String name;
        private String url;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean hasUrl() {
            return url != null && !url.isBlank();
        }

        public String displayName() {
            return name == null || name.isBlank() ? url : name;
        }
    }
}

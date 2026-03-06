package com.example.videobroker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "broker.backoffice")
public class BackofficeRoutingProperties {

    private boolean enabled;
    private String route = "backoffice.room.events.ingest";
    private List<BackofficeEndpoint> endpoints = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public List<BackofficeEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<BackofficeEndpoint> endpoints) {
        this.endpoints = endpoints;
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
    }
}

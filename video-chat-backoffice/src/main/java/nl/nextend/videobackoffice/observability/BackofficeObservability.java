package nl.nextend.videobackoffice.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BackofficeObservability {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeRooms = new AtomicInteger();
    private final AtomicInteger retainedEvents = new AtomicInteger();

    public BackofficeObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("videochat_backoffice_active_rooms", activeRooms, AtomicInteger::get)
            .description("Active rooms currently retained by the backoffice event buffer")
            .register(meterRegistry);
        Gauge.builder("videochat_backoffice_retained_events", retainedEvents, AtomicInteger::get)
            .description("Total retained room events in the backoffice in-memory buffer")
            .register(meterRegistry);
    }

    public void recordIngestedEvent(String eventType, int activeRoomCount, int retainedEventCount) {
        meterRegistry.counter(
            "videochat_backoffice_ingested_events_total",
            "event_type",
            sanitize(eventType)
        ).increment();
        updateRetentionSnapshot(activeRoomCount, retainedEventCount);
    }

    public void updateRetentionSnapshot(int activeRoomCount, int retainedEventCount) {
        activeRooms.set(Math.max(activeRoomCount, 0));
        retainedEvents.set(Math.max(retainedEventCount, 0));
    }

    public void recordFrontendTelemetry(String eventType, String route, String connectionState) {
        meterRegistry.counter(
            "videochat_frontend_telemetry_events_total",
            "event_type",
            sanitize(eventType),
            "route",
            sanitizeRoute(route),
            "connection_state",
            sanitize(connectionState)
        ).increment();
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "_");
    }

    private String sanitizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return "unknown";
        }

        String normalized = route.trim();
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return "/" + normalized;
    }
}

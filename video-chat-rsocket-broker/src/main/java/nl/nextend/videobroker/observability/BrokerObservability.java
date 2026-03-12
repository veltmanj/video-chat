package nl.nextend.videobroker.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BrokerObservability {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeRooms = new AtomicInteger();

    public BrokerObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("videochat_broker_active_rooms", activeRooms, AtomicInteger::get)
            .description("Room channels currently tracked by the broker")
            .register(meterRegistry);
    }

    public void recordAuthorization(String outcome) {
        meterRegistry.counter(
            "videochat_broker_authorizations_total",
            "outcome",
            sanitize(outcome)
        ).increment();
    }

    public void recordPublishedEvent(String eventType) {
        meterRegistry.counter(
            "videochat_broker_published_events_total",
            "event_type",
            sanitize(eventType)
        ).increment();
    }

    public void recordInvalidPublish() {
        meterRegistry.counter(
            "videochat_broker_published_events_total",
            "event_type",
            "invalid"
        ).increment();
    }

    public void recordStreamSubscription() {
        meterRegistry.counter("videochat_broker_stream_subscriptions_total").increment();
    }

    public void recordStreamTermination(SignalType signalType) {
        meterRegistry.counter(
            "videochat_broker_stream_terminations_total",
            "signal",
            sanitize(signalType == null ? null : signalType.name())
        ).increment();
    }

    public void recordBackofficeForward(String endpoint, String outcome) {
        meterRegistry.counter(
            "videochat_broker_backoffice_forward_total",
            "endpoint",
            sanitize(endpoint),
            "outcome",
            sanitize(outcome)
        ).increment();
    }

    public void updateActiveRooms(int roomCount) {
        activeRooms.set(Math.max(roomCount, 0));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "_");
    }
}

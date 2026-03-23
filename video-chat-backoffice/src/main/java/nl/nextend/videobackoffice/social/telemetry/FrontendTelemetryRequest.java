package nl.nextend.videobackoffice.social.telemetry;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable frontend telemetry payload.
 *
 * <p>The compact record keeps ingestion tolerant by treating omitted details as an empty map rather
 * than forcing every client to send the field explicitly.
 */
public record FrontendTelemetryRequest(
    @NotBlank String eventType,
    String route,
    String connectionState,
    String roomId,
    String sessionId,
    Instant occurredAt,
    Map<String, Object> details
) {
    public FrontendTelemetryRequest {
        details = details == null ? Map.of() : details;
    }
}

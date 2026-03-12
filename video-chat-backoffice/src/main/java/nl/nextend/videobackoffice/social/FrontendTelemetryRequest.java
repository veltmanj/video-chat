package nl.nextend.videobackoffice.social;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

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

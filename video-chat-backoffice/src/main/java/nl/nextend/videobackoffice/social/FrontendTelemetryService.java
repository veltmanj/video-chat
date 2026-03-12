package nl.nextend.videobackoffice.social;

import nl.nextend.videobackoffice.observability.BackofficeObservability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class FrontendTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(FrontendTelemetryService.class);

    private final BackofficeObservability observability;

    public FrontendTelemetryService(BackofficeObservability observability) {
        this.observability = observability;
    }

    public void ingest(Jwt jwt, FrontendTelemetryRequest request) {
        observability.recordFrontendTelemetry(request.eventType(), request.route(), request.connectionState());
        log.info(
            "Frontend telemetry event={} route={} state={} room={} session={} user={}",
            request.eventType(),
            normalize(request.route()),
            normalize(request.connectionState()),
            normalize(request.roomId()),
            normalize(request.sessionId()),
            jwt == null ? "unknown" : normalize(jwt.getSubject())
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "n/a" : value.trim();
    }
}

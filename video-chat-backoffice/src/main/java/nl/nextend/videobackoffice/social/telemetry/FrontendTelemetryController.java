package nl.nextend.videobackoffice.social.telemetry;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Receives low-cost client telemetry events from the social frontend.
 */
@RestController
@RequestMapping("/social/v1")
public class FrontendTelemetryController {

    private final FrontendTelemetryService frontendTelemetryService;

    public FrontendTelemetryController(FrontendTelemetryService frontendTelemetryService) {
        this.frontendTelemetryService = frontendTelemetryService;
    }

    @PostMapping("/telemetry")
    Mono<Void> ingest(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FrontendTelemetryRequest request) {
        frontendTelemetryService.ingest(jwt, request);
        return Mono.empty();
    }
}

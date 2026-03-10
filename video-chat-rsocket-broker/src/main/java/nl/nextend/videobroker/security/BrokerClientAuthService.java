package nl.nextend.videobroker.security;

import org.springframework.stereotype.Service;

/**
 * Validates the shared access token required from frontend clients before they can publish or subscribe.
 */
@Service
public class BrokerClientAuthService {

    private final JwtValidatorService jwtValidatorService;

    public BrokerClientAuthService(JwtValidatorService jwtValidatorService) {
        this.jwtValidatorService = jwtValidatorService;
    }

    public void requireAuthorized(String providedToken) {
        JwtValidatorService.ValidationResult result = jwtValidatorService.validateDetailed(providedToken);
        if (!result.valid()) {
            throw new SecurityException(result.reason());
        }
    }
}

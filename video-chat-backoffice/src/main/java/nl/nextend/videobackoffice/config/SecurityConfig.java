package nl.nextend.videobackoffice.config;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobackoffice.social.auth.EmailAuthJwtService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({ BackofficeSocialProperties.class, BackofficeAiProperties.class })
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .pathMatchers("/api/**").permitAll()
                .pathMatchers("/rsocket", "/rsocket/**").permitAll()
                .pathMatchers("/social/v1/auth/**").permitAll()
                .pathMatchers("/social/**").authenticated()
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(BackofficeSocialProperties properties, EmailAuthJwtService emailAuthJwtService) {
        ReactiveJwtDecoder googleDecoder = googleJwtDecoder(properties);
        ReactiveJwtDecoder emailDecoder = emailJwtDecoder(properties, emailAuthJwtService);

        return token -> {
            String issuer = extractIssuer(token);
            if (properties.getEmail().isEnabled() && issuerMatches(issuer, properties.getEmail().getIssuer())) {
                return emailDecoder.decode(token);
            }
            return googleDecoder.decode(token);
        };
    }

    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    private ReactiveJwtDecoder googleJwtDecoder(BackofficeSocialProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.getAuth().getGoogleJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.getAuth().getGoogleIssuer()),
            audienceValidator(properties.getAuth().getGoogleAudience())
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private ReactiveJwtDecoder emailJwtDecoder(BackofficeSocialProperties properties, EmailAuthJwtService emailAuthJwtService) {
        RSAPublicKey publicKey = emailAuthJwtService.publicKey();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(publicKey).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.getEmail().getIssuer()),
            audienceValidator(properties.getEmail().getAudience())
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private String extractIssuer(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (Exception exception) {
            throw new BadJwtException("Token issuer could not be resolved.", exception);
        }
    }

    private OAuth2TokenValidator<Jwt> audienceValidator(String expectedAudience) {
        return token -> {
            if (!StringUtils.hasText(expectedAudience)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE is not configured.",
                    null
                ));
            }

            List<String> audiences = token.getAudience();
            return audiences.contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Token audience is not allowed.",
                    null
                ));
        };
    }

    private boolean issuerMatches(String actualIssuer, String expectedIssuer) {
        return StringUtils.hasText(actualIssuer)
            && StringUtils.hasText(expectedIssuer)
            && actualIssuer.trim().equalsIgnoreCase(expectedIssuer.trim());
    }
}

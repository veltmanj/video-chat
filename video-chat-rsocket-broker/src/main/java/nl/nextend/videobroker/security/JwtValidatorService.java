package nl.nextend.videobroker.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobroker.config.BrokerJwtProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class JwtValidatorService {
    private static final Logger log = LoggerFactory.getLogger(JwtValidatorService.class);

    private final BrokerJwtProperties properties;
    private final VaultJwkSetService vaultJwkSetService;
    private final Clock clock;

    @Autowired
    public JwtValidatorService(BrokerJwtProperties properties, VaultJwkSetService vaultJwkSetService) {
        this(properties, vaultJwkSetService, Clock.systemUTC());
    }

    JwtValidatorService(BrokerJwtProperties properties, VaultJwkSetService vaultJwkSetService, Clock clock) {
        this.properties = properties;
        this.vaultJwkSetService = vaultJwkSetService;
        this.clock = clock;
    }

    public boolean validate(String token) {
        return validateDetailed(token).valid();
    }

    ValidationResult validateDetailed(String token) {
        if (!properties.isEnabled()) {
            return ValidationResult.success();
        }

        if (!StringUtils.hasText(token)) {
            return ValidationResult.failure("Unauthorized broker client: missing broker JWT.");
        }

        try {
            SignedJWT jwt = SignedJWT.parse(token);
            List<Provider> candidates = resolveCandidateProviders(jwt);
            if (candidates.isEmpty()) {
                return ValidationResult.failure(String.format(
                    "Unauthorized broker client: no configured JWT provider matched issuer=%s.",
                    safeClaim(jwt.getJWTClaimsSet().getIssuer())
                ));
            }

            String lastFailure = "Unauthorized broker client: JWT validation failed.";
            for (Provider provider : candidates) {
                ValidationResult providerResult = verifyWithProvider(jwt, provider);
                if (providerResult.valid()) {
                    return providerResult;
                }

                lastFailure = providerResult.reason();
            }

            return ValidationResult.failure(lastFailure);
        } catch (ParseException e) {
            return ValidationResult.failure("Unauthorized broker client: supplied token is not a valid JWT.");
        } catch (Exception exception) {
            log.warn("JWT validation failed unexpectedly", exception);
            return ValidationResult.failure("Unauthorized broker client: JWT validation failed unexpectedly.");
        }
    }

    private List<Provider> resolveCandidateProviders(SignedJWT jwt) throws java.text.ParseException {
        String issuer = jwt.getJWTClaimsSet().getIssuer();
        List<Provider> enabledProviders = properties.getProviders().stream()
            .filter(Provider::isEnabled)
            .filter(provider -> StringUtils.hasText(provider.getName()))
            .filter(this::hasJwkSourceConfigured)
            .toList();

        List<Provider> genericProviders = enabledProviders.stream()
            .filter(provider -> provider.getIssuers().isEmpty())
            .toList();

        if (!StringUtils.hasText(issuer)) {
            return genericProviders;
        }

        List<Provider> issuerMatches = enabledProviders.stream()
            .filter(provider -> provider.getIssuers().stream().anyMatch(configuredIssuer -> issuerMatches(issuer, configuredIssuer)))
            .toList();

        if (issuerMatches.isEmpty()) {
            return genericProviders;
        }

        return java.util.stream.Stream.concat(issuerMatches.stream(), genericProviders.stream())
            .distinct()
            .toList();
    }

    private ValidationResult verifyWithProvider(SignedJWT jwt, Provider provider) {
        try {
            JWKSet jwkSet = vaultJwkSetService.load(provider);
            String keyId = jwt.getHeader().getKeyID();
            String lastFailure = String.format(
                "Unauthorized broker client: signature verification failed for provider=%s kid=%s.",
                provider.getName(),
                safeClaim(keyId)
            );

            for (RSAKey rsaKey : selectCandidateKeys(jwkSet, jwt.getHeader().getKeyID())) {
                RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                JWSVerifier verifier = new RSASSAVerifier(publicKey);
                if (!jwt.verify(verifier)) {
                    continue;
                }

                ValidationResult claimsResult = validateClaims(jwt, provider);
                if (claimsResult.valid()) {
                    return claimsResult;
                }

                lastFailure = claimsResult.reason();
            }
            return ValidationResult.failure(lastFailure);
        } catch (Exception exception) {
            log.warn("JWT verification failed for provider={}", provider.getName(), exception);
            return ValidationResult.failure(String.format(
                "Unauthorized broker client: JWT verification failed for provider=%s.",
                provider.getName()
            ));
        }
    }

    private boolean hasJwkSourceConfigured(Provider provider) {
        return StringUtils.hasText(provider.getJwkSetJson())
            || StringUtils.hasText(provider.getJwkSetUri())
            || StringUtils.hasText(provider.getVaultPath());
    }

    private List<RSAKey> selectCandidateKeys(JWKSet jwkSet, String keyId) {
        List<RSAKey> rsaKeys = jwkSet.getKeys().stream()
            .filter(RSAKey.class::isInstance)
            .map(RSAKey.class::cast)
            .filter(this::isSigningKey)
            .collect(Collectors.toList());

        if (!StringUtils.hasText(keyId)) {
            return rsaKeys;
        }

        List<RSAKey> matchingKeyId = rsaKeys.stream()
            .filter(jwk -> keyId.equals(jwk.getKeyID()))
            .toList();

        return matchingKeyId.isEmpty() ? rsaKeys : matchingKeyId;
    }

    private boolean issuerMatches(String tokenIssuer, String configuredIssuer) {
        return StringUtils.hasText(configuredIssuer)
            && tokenIssuer.trim().toLowerCase(Locale.ROOT).equals(configuredIssuer.trim().toLowerCase(Locale.ROOT));
    }

    private ValidationResult validateClaims(SignedJWT jwt, Provider provider) throws ParseException {
        var claims = jwt.getJWTClaimsSet();

        if (!hasValidIssuer(claims.getIssuer(), provider)) {
            return ValidationResult.failure(String.format(
                "Unauthorized broker client: issuer mismatch for provider=%s expected=%s actual=%s.",
                provider.getName(),
                provider.getIssuers(),
                safeClaim(claims.getIssuer())
            ));
        }

        if (!hasValidAudience(claims.getAudience(), provider)) {
            return ValidationResult.failure(String.format(
                "Unauthorized broker client: audience mismatch for provider=%s expected=%s actual=%s.",
                provider.getName(),
                provider.getAudiences(),
                claims.getAudience()
            ));
        }

        if (!StringUtils.hasText(claims.getSubject())) {
            return ValidationResult.failure(String.format(
                "Unauthorized broker client: subject missing for provider=%s.",
                provider.getName()
            ));
        }

        if (!hasValidTimestamps(claims.getExpirationTime(), claims.getNotBeforeTime(), claims.getIssueTime())) {
            return ValidationResult.failure(String.format(
                "Unauthorized broker client: token timestamps are not valid for provider=%s exp=%s nbf=%s iat=%s now=%s.",
                provider.getName(),
                claims.getExpirationTime(),
                claims.getNotBeforeTime(),
                claims.getIssueTime(),
                Instant.now(clock)
            ));
        }

        return ValidationResult.success();
    }

    private boolean hasValidIssuer(String issuer, Provider provider) {
        if (provider.getIssuers().isEmpty()) {
            return true;
        }

        return provider.getIssuers().stream().anyMatch(configuredIssuer -> issuerMatches(issuer, configuredIssuer));
    }

    private boolean hasValidAudience(List<String> tokenAudiences, Provider provider) {
        if (provider.getAudiences().isEmpty()) {
            return true;
        }

        return tokenAudiences != null
            && tokenAudiences.stream().anyMatch(tokenAudience ->
                provider.getAudiences().stream().anyMatch(configuredAudience -> audienceMatches(tokenAudience, configuredAudience))
            );
    }

    private boolean audienceMatches(String tokenAudience, String configuredAudience) {
        return StringUtils.hasText(tokenAudience)
            && StringUtils.hasText(configuredAudience)
            && tokenAudience.trim().equals(configuredAudience.trim());
    }

    private boolean hasValidTimestamps(Date expirationTime, Date notBeforeTime, Date issuedAtTime) {
        if (expirationTime == null) {
            return false;
        }

        Instant now = Instant.now(clock);
        Duration clockSkew = properties.getClockSkew() == null ? Duration.ZERO : properties.getClockSkew().abs();
        Instant skewedNow = now.plus(clockSkew);

        if (expirationTime.toInstant().isBefore(now.minus(clockSkew))) {
            return false;
        }

        if (notBeforeTime != null && notBeforeTime.toInstant().isAfter(skewedNow)) {
            return false;
        }

        return issuedAtTime == null || !issuedAtTime.toInstant().isAfter(skewedNow);
    }

    private boolean isSigningKey(RSAKey key) {
        KeyUse keyUse = key.getKeyUse();
        return keyUse == null || KeyUse.SIGNATURE.equals(keyUse);
    }

    private String safeClaim(String value) {
        return StringUtils.hasText(value) ? value : "<empty>";
    }

    record ValidationResult(boolean valid, String reason) {
        static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        static ValidationResult failure(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}

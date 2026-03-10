package nl.nextend.videobroker.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobroker.config.BrokerJwtProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties.Provider;
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
        if (!properties.isEnabled()) {
            return true;
        }

        if (!StringUtils.hasText(token)) {
            return false;
        }

        try {
            SignedJWT jwt = SignedJWT.parse(token);
            List<Provider> candidates = resolveCandidateProviders(jwt);
            if (candidates.isEmpty()) {
                return false;
            }

            for (Provider provider : candidates) {
                if (verifyWithProvider(jwt, provider)) {
                    return true;
                }
            }

            return false;
        } catch (ParseException e) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Provider> resolveCandidateProviders(SignedJWT jwt) throws java.text.ParseException {
        String issuer = jwt.getJWTClaimsSet().getIssuer();
        List<Provider> enabledProviders = properties.getProviders().stream()
            .filter(Provider::isEnabled)
            .filter(provider -> StringUtils.hasText(provider.getName()))
            .filter(provider -> StringUtils.hasText(provider.getVaultPath()))
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

    private boolean verifyWithProvider(SignedJWT jwt, Provider provider) {
        try {
            JWKSet jwkSet = vaultJwkSetService.load(provider);
            for (RSAKey rsaKey : selectCandidateKeys(jwkSet, jwt.getHeader().getKeyID())) {
                RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                JWSVerifier verifier = new RSASSAVerifier(publicKey);
                if (jwt.verify(verifier) && validateClaims(jwt, provider)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
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

    private boolean validateClaims(SignedJWT jwt, Provider provider) throws ParseException {
        var claims = jwt.getJWTClaimsSet();

        if (!hasValidIssuer(claims.getIssuer(), provider)) {
            return false;
        }

        if (!hasValidAudience(claims.getAudience(), provider)) {
            return false;
        }

        if (!StringUtils.hasText(claims.getSubject())) {
            return false;
        }

        return hasValidTimestamps(claims.getExpirationTime(), claims.getNotBeforeTime(), claims.getIssueTime());
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
}

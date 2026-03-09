package nl.nextend.videobroker.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobroker.config.BrokerJwtProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties.Provider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class JwtValidatorService {

    private final BrokerJwtProperties properties;
    private final VaultJwkSetService vaultJwkSetService;

    public JwtValidatorService(BrokerJwtProperties properties, VaultJwkSetService vaultJwkSetService) {
        this.properties = properties;
        this.vaultJwkSetService = vaultJwkSetService;
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

        if (!StringUtils.hasText(issuer)) {
            return enabledProviders;
        }

        List<Provider> issuerMatches = enabledProviders.stream()
            .filter(provider -> provider.getIssuers().stream().anyMatch(configuredIssuer -> issuerMatches(issuer, configuredIssuer)))
            .toList();

        return issuerMatches.isEmpty() ? enabledProviders : issuerMatches;
    }

    private boolean verifyWithProvider(SignedJWT jwt, Provider provider) {
        try {
            JWKSet jwkSet = vaultJwkSetService.load(provider);
            for (RSAKey rsaKey : selectCandidateKeys(jwkSet, jwt.getHeader().getKeyID())) {
                RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                JWSVerifier verifier = new RSASSAVerifier(publicKey);
                if (jwt.verify(verifier)) {
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
}

package nl.nextend.videobroker.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobroker.config.BrokerJwtProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties.Provider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtValidatorServiceTest {

    @Test
    void shouldValidateTokenAgainstMatchingProviderIssuer() throws Exception {
        BrokerJwtProperties properties = new BrokerJwtProperties();
        properties.setEnabled(true);
        properties.setProviders(List.of(provider("google", List.of("https://accounts.google.com"), "jwt/providers/google")));

        RSAKey rsaKey = new RSAKeyGenerator(2048)
            .keyID("kid-google")
            .generate();

        VaultJwkSetService vaultJwkSetService = mock(VaultJwkSetService.class);
        when(vaultJwkSetService.load(properties.getProviders().get(0)))
            .thenReturn(new JWKSet(rsaKey.toPublicJWK()));

        JwtValidatorService validatorService = new JwtValidatorService(properties, vaultJwkSetService);

        assertThat(validatorService.validate(createToken(rsaKey, "https://accounts.google.com"))).isTrue();
        assertThat(validatorService.validate(createToken(rsaKey, "https://issuer.example.invalid"))).isTrue();
        assertThat(validatorService.validate("not-a-jwt")).isFalse();
    }

    private Provider provider(String name, List<String> issuers, String vaultPath) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setIssuers(issuers);
        provider.setVaultPath(vaultPath);
        return provider;
    }

    private String createToken(RSAKey rsaKey, String issuer) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-123")
                .issueTime(java.util.Date.from(Instant.parse("2026-03-09T12:00:00Z")))
                .expirationTime(java.util.Date.from(Instant.parse("2026-03-09T13:00:00Z")))
                .build()
        );
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return jwt.serialize();
    }
}

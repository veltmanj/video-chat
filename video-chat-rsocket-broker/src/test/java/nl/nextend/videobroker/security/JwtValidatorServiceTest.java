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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtValidatorServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-03-09T12:00:00Z");

    private BrokerJwtProperties properties;
    private Provider googleProvider;
    private RSAKey rsaKey;
    private VaultJwkSetService vaultJwkSetService;
    private JwtValidatorService validatorService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BrokerJwtProperties();
        properties.setEnabled(true);
        properties.setClockSkew(java.time.Duration.ofSeconds(30));

        googleProvider = provider(
            "google",
            List.of("https://accounts.google.com", "accounts.google.com"),
            List.of("google-web-client-id"),
            "jwt/providers/google"
        );
        properties.setProviders(List.of(googleProvider));

        rsaKey = new RSAKeyGenerator(2048)
            .keyID("kid-google")
            .generate();

        vaultJwkSetService = mock(VaultJwkSetService.class);
        when(vaultJwkSetService.load(googleProvider))
            .thenReturn(new JWKSet(rsaKey.toPublicJWK()));

        validatorService = new JwtValidatorService(
            properties,
            vaultJwkSetService,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldValidateTokenAgainstMatchingProviderIssuerAudienceAndLifetime() throws Exception {
        assertThat(validatorService.validate(validToken().serialize())).isTrue();
    }

    @Test
    void shouldRejectTokenWhenIssuerDoesNotMatchConfiguredProvider() throws Exception {
        assertThat(validatorService.validate(tokenBuilder()
            .issuer("https://issuer.example.invalid")
            .build()
            .serialize())).isFalse();
    }

    @Test
    void shouldRejectTokenWhenAudienceDoesNotMatchConfiguredProvider() throws Exception {
        assertThat(validatorService.validate(tokenBuilder()
            .audience("unexpected-audience")
            .build()
            .serialize())).isFalse();
    }

    @Test
    void shouldRejectTokenWhenExpired() throws Exception {
        assertThat(validatorService.validate(tokenBuilder()
            .expirationTime(Date.from(FIXED_NOW.minusSeconds(31)))
            .build()
            .serialize())).isFalse();
    }

    @Test
    void shouldRejectTokenWhenNotYetValid() throws Exception {
        assertThat(validatorService.validate(tokenBuilder()
            .notBeforeTime(Date.from(FIXED_NOW.plusSeconds(31)))
            .build()
            .serialize())).isFalse();
    }

    @Test
    void shouldRejectTokenMissingExpirationOrSubject() throws Exception {
        assertThat(validatorService.validate(tokenBuilder()
            .expirationTime(null)
            .build()
            .serialize())).isFalse();

        assertThat(validatorService.validate(tokenBuilder()
            .subject(null)
            .build()
            .serialize())).isFalse();
    }

    @Test
    void shouldRejectMalformedToken() {
        assertThat(validatorService.validate("not-a-jwt")).isFalse();
    }

    private Provider provider(String name, List<String> issuers, List<String> audiences, String vaultPath) {
        Provider provider = new Provider();
        provider.setName(name);
        provider.setIssuers(issuers);
        provider.setAudiences(audiences);
        provider.setVaultPath(vaultPath);
        return provider;
    }

    private SignedJWT validToken() throws JOSEException {
        return tokenBuilder().build();
    }

    private TokenBuilder tokenBuilder() {
        return new TokenBuilder()
            .issuer("https://accounts.google.com")
            .subject("user-123")
            .audience("google-web-client-id")
            .issueTime(Date.from(FIXED_NOW.minusSeconds(10)))
            .notBeforeTime(Date.from(FIXED_NOW.minusSeconds(10)))
            .expirationTime(Date.from(FIXED_NOW.plusSeconds(300)));
    }

    private final class TokenBuilder {
        private String issuer;
        private String subject;
        private String audience;
        private Date issueTime;
        private Date notBeforeTime;
        private Date expirationTime;

        private TokenBuilder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        private TokenBuilder subject(String subject) {
            this.subject = subject;
            return this;
        }

        private TokenBuilder audience(String audience) {
            this.audience = audience;
            return this;
        }

        private TokenBuilder issueTime(Date issueTime) {
            this.issueTime = issueTime;
            return this;
        }

        private TokenBuilder notBeforeTime(Date notBeforeTime) {
            this.notBeforeTime = notBeforeTime;
            return this;
        }

        private TokenBuilder expirationTime(Date expirationTime) {
            this.expirationTime = expirationTime;
            return this;
        }

        private SignedJWT build() throws JOSEException {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            if (issuer != null) {
                claims.issuer(issuer);
            }
            if (subject != null) {
                claims.subject(subject);
            }
            if (audience != null) {
                claims.audience(audience);
            }
            if (issueTime != null) {
                claims.issueTime(issueTime);
            }
            if (notBeforeTime != null) {
                claims.notBeforeTime(notBeforeTime);
            }
            if (expirationTime != null) {
                claims.expirationTime(expirationTime);
            }

            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims.build()
            );
            jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
            return jwt;
        }
    }
}

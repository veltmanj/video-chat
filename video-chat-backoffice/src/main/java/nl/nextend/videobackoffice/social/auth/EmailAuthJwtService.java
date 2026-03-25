package nl.nextend.videobackoffice.social.auth;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.repository.EmailAuthJdbcRepository.EmailAccountRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmailAuthJwtService {

    private static final Logger log = LoggerFactory.getLogger(EmailAuthJwtService.class);

    private final BackofficeSocialProperties properties;
    private final Clock clock;
    private final RSAKey signingKey;
    private final JWKSet publicJwkSet;

    @Autowired
    public EmailAuthJwtService(BackofficeSocialProperties properties) {
        this.properties = properties;
        this.clock = Clock.systemUTC();
        this.signingKey = loadSigningKey(properties);
        this.publicJwkSet = new JWKSet(signingKey.toPublicJWK());
    }

    EmailAuthJwtService(BackofficeSocialProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = loadSigningKey(properties);
        this.publicJwkSet = new JWKSet(signingKey.toPublicJWK());
    }

    public RSAPublicKey publicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve the email-auth public key.", exception);
        }
    }

    public String jwksJson() {
        return publicJwkSet.toString();
    }

    public String issueSessionToken(EmailAccountRow account) {
        try {
            Instant now = Instant.now(clock);
            Instant expiresAt = now.plus(properties.getEmail().getSessionTtl());
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.getEmail().getIssuer())
                .audience(properties.getEmail().getAudience())
                .subject("email:" + account.id())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("name", account.displayName())
                .claim("email", account.email())
                .claim("email_verified", true)
                .claim("provider", "email")
                .jwtID(UUID.randomUUID().toString())
                .build();

            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .keyID(signingKey.getKeyID())
                    .build(),
                claims
            );
            jwt.sign(new RSASSASigner((RSAPrivateKey) signingKey.toPrivateKey()));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to issue an email-auth session token.", exception);
        }
    }

    private RSAKey loadSigningKey(BackofficeSocialProperties properties) {
        String configuredJwkJson = properties.getEmail().getJwkJson();
        if (StringUtils.hasText(configuredJwkJson)) {
            try {
                RSAKey key = RSAKey.parse(configuredJwkJson);
                if (key.toPrivateKey() == null) {
                    throw new IllegalStateException("BACKOFFICE_SOCIAL_EMAIL_JWK_JSON must include a private key.");
                }
                return key;
            } catch (Exception exception) {
                throw new IllegalStateException("BACKOFFICE_SOCIAL_EMAIL_JWK_JSON is not a valid RSA JWK.", exception);
            }
        }

        try {
            log.warn("BACKOFFICE_SOCIAL_EMAIL_JWK_JSON is not configured; generating an ephemeral email-auth signing key.");
            return new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .generate();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate the email-auth signing key.", exception);
        }
    }
}

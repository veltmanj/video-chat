package nl.nextend.videobroker.integration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import nl.nextend.videobroker.model.RoomEventMessage;
import nl.nextend.videobroker.model.RoomPublishRequest;
import nl.nextend.videobroker.model.RoomStreamRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "broker.backoffice.enabled=false",
        "broker.jwt.enabled=false"
    }
)
@EnabledIfSystemProperty(named = "liveStackSecurityProbe", matches = "true")
class LiveStackSecurityProbeTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private RSocketRequester.Builder requesterBuilder;

    private RSocketRequester requester;

    @AfterEach
    void cleanUp() {
        if (requester != null) {
            requester.rsocketClient().dispose();
            requester = null;
        }
    }

    @Test
    void shouldRejectMalformedTokenOnAuthorize() {
        requester = requesterBuilder.transport(WebsocketClientTransport.create(brokerUri()));

        RoomStreamRequest request = new RoomStreamRequest(
            "AUTHORIZE",
            "room.events.authorize",
            "security-drill-room",
            "security-drill-client",
            "not-a-jwt"
        );

        assertThatThrownBy(() -> requester.route("room.events.authorize")
            .data(request)
            .retrieveMono(Void.class)
            .block(REQUEST_TIMEOUT))
            .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectMalformedTokenOnPublish() {
        requester = requesterBuilder.transport(WebsocketClientTransport.create(brokerUri()));

        RoomPublishRequest request = new RoomPublishRequest(
            "ROOM_EVENT",
            "room.events.publish",
            "not-a-jwt",
            new RoomEventMessage(
                "ROOM_JOINED",
                "security-drill-room",
                "security-drill-attacker",
                "Security Drill",
                null,
                Map.of("probe", "malformed-token")
            )
        );

        assertThatThrownBy(() -> requester.route("room.events.publish")
            .data(request)
            .retrieveMono(Void.class)
            .block(REQUEST_TIMEOUT))
            .isInstanceOf(Exception.class);
    }

    @Test
    void shouldRejectAttackerControlledProviderKey() throws Exception {
        String vaultToken = requiredSystemProperty("securityProbeVaultToken");
        String attackVaultPath = System.getProperty("securityProbeAttackVaultPath", "");

        assumeTrue(StringUtils.hasText(attackVaultPath), "No attack provider path configured; skipping provider-injection probe.");

        RSAKey attackerKey = new RSAKeyGenerator(2048)
            .keyID("security-drill-kid")
            .generate();

        writeAttackJwks(vaultToken, attackVaultPath, new JWKSet(attackerKey.toPublicJWK()).toString());

        requester = requesterBuilder.transport(WebsocketClientTransport.create(brokerUri()));

        RoomStreamRequest request = new RoomStreamRequest(
            "AUTHORIZE",
            "room.events.authorize",
            "security-drill-room",
            "security-drill-client",
            signedAttackToken(attackerKey)
        );

        assertThatThrownBy(() -> requester.route("room.events.authorize")
            .data(request)
            .retrieveMono(Void.class)
            .block(REQUEST_TIMEOUT))
            .isInstanceOf(Exception.class);
    }

    private URI brokerUri() {
        return URI.create(System.getProperty("securityProbeBrokerUri", "ws://broker:9898/rsocket"));
    }

    private String vaultUri() {
        return System.getProperty("securityProbeVaultUri", "http://vault:8200");
    }

    private String vaultMount() {
        return System.getProperty("securityProbeAttackVaultMount", "secret");
    }

    private String requiredSystemProperty(String name) {
        String value = System.getProperty(name, "");
        assumeTrue(StringUtils.hasText(value), "Missing required system property: " + name);
        return value;
    }

    private void writeAttackJwks(String vaultToken, String vaultPath, String jwksJson) {
        WebClient.builder()
            .baseUrl(vaultUri())
            .build()
            .post()
            .uri("/v1/" + vaultMount() + "/data/" + vaultPath)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .header("X-Vault-Token", vaultToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("data", Map.of("jwks_json", jwksJson)))
            .retrieve()
            .toBodilessEntity()
            .block(REQUEST_TIMEOUT);
    }

    private String signedAttackToken(RSAKey rsaKey) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer("https://attacker.example.invalid")
                .subject("security-drill-attacker")
                .audience("attacker-client-id")
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .build()
        );
        jwt.sign(new RSASSASigner(rsaKey.toPrivateKey()));
        return jwt.serialize();
    }
}

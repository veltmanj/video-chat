package nl.nextend.videobroker.security;

import com.nimbusds.jose.jwk.JWKSet;
import nl.nextend.videobroker.config.BrokerJwtProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties.Provider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class VaultJwkSetService {

    private final BrokerJwtProperties properties;
    private final WebClient webClient;
    private final ConcurrentMap<String, CachedJwkSet> cache = new ConcurrentHashMap<>();

    public VaultJwkSetService(BrokerJwtProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder().build();
    }

    public JWKSet load(Provider provider) {
        CachedJwkSet cached = cache.get(cacheKey(provider));
        if (cached != null && !cached.isExpired()) {
            return cached.jwkSet();
        }

        JWKSet fetched = fetch(provider);
        cache.put(cacheKey(provider), new CachedJwkSet(fetched, expiresAt()));
        return fetched;
    }

    private JWKSet fetch(Provider provider) {
        Map<?, ?> response = webClient.get()
            .uri(buildSecretUri(provider))
            .header(HttpHeaders.ACCEPT, "application/json")
            .header("X-Vault-Token", properties.getVault().getToken())
            .retrieve()
            .bodyToMono(Map.class)
            .block(requestTimeout());

        if (response == null) {
            throw new IllegalStateException("Vault returned no response for provider " + provider.getName());
        }

        String jwksJson = extractSecretValue(response, provider);
        if (!StringUtils.hasText(jwksJson)) {
            throw new IllegalStateException(
                "Vault secret " + provider.getVaultPath() + " is missing field " + provider.getVaultField()
            );
        }

        try {
            return JWKSet.parse(jwksJson);
        } catch (Exception error) {
            throw new IllegalStateException(
                "Vault secret " + provider.getVaultPath() + " does not contain a valid JWKS document",
                error
            );
        }
    }

    private String buildSecretUri(Provider provider) {
        String baseUri = properties.getVault().getUri().toString().replaceAll("/+$", "");
        String mount = UriUtils.encodePathSegment(properties.getVault().getKvMount(), StandardCharsets.UTF_8);
        String path = UriUtils.encodePath(provider.getVaultPath(), StandardCharsets.UTF_8);
        return baseUri + "/v1/" + mount + "/data/" + path;
    }

    private Instant expiresAt() {
        return Instant.now().plus(properties.getCacheTtl());
    }

    private Duration requestTimeout() {
        Duration timeout = properties.getVault().getRequestTimeout();
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }

    private String cacheKey(Provider provider) {
        return provider.getName() + "|" + provider.getVaultPath() + "|" + provider.getVaultField();
    }

    @SuppressWarnings("unchecked")
    private String extractSecretValue(Map<?, ?> response, Provider provider) {
        Object dataNode = response.get("data");
        if (!(dataNode instanceof Map<?, ?> data)) {
            return null;
        }

        Object secretNode = data.get("data");
        if (!(secretNode instanceof Map<?, ?> secret)) {
            return null;
        }

        Object fieldValue = secret.get(provider.getVaultField());
        return fieldValue == null ? null : Objects.toString(fieldValue, null);
    }

    private record CachedJwkSet(JWKSet jwkSet, Instant expiresAt) {
        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}

package nl.nextend.videobroker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "broker.jwt")
public class BrokerJwtProperties {

    private boolean enabled = false;
    private Duration cacheTtl = Duration.ofMinutes(15);
    private Duration clockSkew = Duration.ofSeconds(30);
    private final Vault vault = new Vault();
    private List<Provider> providers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Vault getVault() {
        return vault;
    }

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : new ArrayList<>(providers);
    }

    public static final class Vault {

        private URI uri = URI.create("http://localhost:8200");
        private String token = "";
        private String kvMount = "secret";
        private Duration requestTimeout = Duration.ofSeconds(5);

        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token.trim();
        }

        public String getKvMount() {
            return kvMount;
        }

        public void setKvMount(String kvMount) {
            this.kvMount = kvMount == null ? "secret" : kvMount.trim();
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static final class Provider {

        private boolean enabled = true;
        private String name = "";
        private List<String> issuers = new ArrayList<>();
        private List<String> audiences = new ArrayList<>();
        private String vaultPath = "";
        private String vaultField = "jwks_json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public List<String> getIssuers() {
            return issuers;
        }

        public void setIssuers(List<String> issuers) {
            this.issuers = normalizeValues(issuers);
        }

        public List<String> getAudiences() {
            return audiences;
        }

        public void setAudiences(List<String> audiences) {
            this.audiences = normalizeValues(audiences);
        }

        public String getVaultPath() {
            return vaultPath;
        }

        public void setVaultPath(String vaultPath) {
            this.vaultPath = vaultPath == null ? "" : vaultPath.trim();
        }

        public String getVaultField() {
            return vaultField;
        }

        public void setVaultField(String vaultField) {
            this.vaultField = vaultField == null ? "jwks_json" : vaultField.trim();
        }

        private List<String> normalizeValues(List<String> values) {
            if (values == null) {
                return new ArrayList<>();
            }

            return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        }
    }
}

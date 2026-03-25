package nl.nextend.videobackoffice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("backoffice.social")
public class BackofficeSocialProperties {

    private final Auth auth = new Auth();
    private final Email email = new Email();
    private final Media media = new Media();
    private int feedLimit = 50;
    private int searchLimit = 20;

    public Auth getAuth() {
        return auth;
    }

    public Email getEmail() {
        return email;
    }

    public int getFeedLimit() {
        return feedLimit;
    }

    public void setFeedLimit(int feedLimit) {
        this.feedLimit = feedLimit;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    public Media getMedia() {
        return media;
    }

    public static class Auth {
        private String googleIssuer = "https://accounts.google.com";
        private String googleJwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";
        private String googleAudience = "";

        public String getGoogleIssuer() {
            return googleIssuer;
        }

        public void setGoogleIssuer(String googleIssuer) {
            this.googleIssuer = googleIssuer;
        }

        public String getGoogleJwkSetUri() {
            return googleJwkSetUri;
        }

        public void setGoogleJwkSetUri(String googleJwkSetUri) {
            this.googleJwkSetUri = googleJwkSetUri;
        }

        public String getGoogleAudience() {
            return googleAudience;
        }

        public void setGoogleAudience(String googleAudience) {
            this.googleAudience = googleAudience;
        }
    }

    public static class Media {
        private boolean enabled;
        private long maxUploadBytes = 25L * 1024L * 1024L;
        private int maxFilesPerPost = 4;
        private String endpoint = "";
        private String accessKey = "";
        private String secretKey = "";
        private String bucket = "social-media";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public int getMaxFilesPerPost() {
            return maxFilesPerPost;
        }

        public void setMaxFilesPerPost(int maxFilesPerPost) {
            this.maxFilesPerPost = maxFilesPerPost;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }
    }

    public static class Email {
        private boolean enabled;
        private String issuer = "https://localhost/social-api/social/v1/auth";
        private String audience = "pulseroom-email";
        private String jwkJson = "";
        private String publicBaseUrl = "https://localhost";
        private String loginPath = "/login";
        private Duration registrationLinkTtl = Duration.ofHours(24);
        private Duration loginLinkTtl = Duration.ofMinutes(20);
        private Duration sessionTtl = Duration.ofHours(12);
        private String fromAddress = "no-reply@pulseroom.local";
        private String fromName = "PulseRoom";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getJwkJson() {
            return jwkJson;
        }

        public void setJwkJson(String jwkJson) {
            this.jwkJson = jwkJson;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public Duration getRegistrationLinkTtl() {
            return registrationLinkTtl;
        }

        public void setRegistrationLinkTtl(Duration registrationLinkTtl) {
            this.registrationLinkTtl = registrationLinkTtl;
        }

        public Duration getLoginLinkTtl() {
            return loginLinkTtl;
        }

        public void setLoginLinkTtl(Duration loginLinkTtl) {
            this.loginLinkTtl = loginLinkTtl;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
        }

        public String getFromName() {
            return fromName;
        }

        public void setFromName(String fromName) {
            this.fromName = fromName;
        }
    }
}

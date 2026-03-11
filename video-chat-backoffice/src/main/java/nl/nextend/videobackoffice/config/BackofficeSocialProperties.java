package nl.nextend.videobackoffice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("backoffice.social")
public class BackofficeSocialProperties {

    private final Auth auth = new Auth();
    private final Media media = new Media();
    private int feedLimit = 50;
    private int searchLimit = 20;

    public Auth getAuth() {
        return auth;
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
}

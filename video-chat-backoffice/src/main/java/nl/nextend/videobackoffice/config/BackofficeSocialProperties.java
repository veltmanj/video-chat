package nl.nextend.videobackoffice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("backoffice.social")
public class BackofficeSocialProperties {

    private final Auth auth = new Auth();
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
}

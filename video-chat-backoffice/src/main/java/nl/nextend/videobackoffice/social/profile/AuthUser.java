package nl.nextend.videobackoffice.social.profile;

import java.util.Locale;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

/**
 * Authenticated user projection derived from a JWT.
 *
 * <p>The social profile subsystem uses this record as a stable hand-off from authentication claims
 * into local profile creation and handle derivation logic.
 */
public record AuthUser(String subject, String email, String displayName, String avatarUrl) {

    static AuthUser fromJwt(Jwt jwt) {
        String subject = trim(jwt.getSubject());
        String email = trim(jwt.getClaimAsString("email"));
        String displayName = trim(jwt.getClaimAsString("name"));
        String avatarUrl = trim(jwt.getClaimAsString("picture"));

        if (!StringUtils.hasText(displayName)) {
            displayName = StringUtils.hasText(email) ? email : subject;
        }

        return new AuthUser(subject, email, displayName, avatarUrl);
    }

    String suggestedHandleBase() {
        String source = StringUtils.hasText(email) ? email.substring(0, email.indexOf('@')) : displayName;
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        if (!StringUtils.hasText(normalized)) {
            return "user";
        }

        return normalized.substring(0, Math.min(normalized.length(), 24));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

package nl.nextend.videobackoffice.social.profile;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AuthUserTest {

    @Test
    void fromJwtFallsBackToEmailWhenDisplayNameIsMissing() {
        AuthUser user = AuthUser.fromJwt(Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("subject-1")
            .claim("email", "alice@example.com")
            .build());

        assertThat(user.displayName()).isEqualTo("alice@example.com");
        assertThat(user.suggestedHandleBase()).isEqualTo("alice");
    }

    @Test
    void suggestedHandleBaseFallsBackToUserWhenSourceContainsNoUsableCharacters() {
        AuthUser user = new AuthUser("subject-1", "", "!!!", "");

        assertThat(user.suggestedHandleBase()).isEqualTo("user");
    }
}

package nl.nextend.videobackoffice.integration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import nl.nextend.videobackoffice.social.api.SocialApi.EmailAuthStartResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailLoginRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailRegistrationRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.ViewerResponse;
import nl.nextend.videobackoffice.social.auth.EmailAuthMailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "backoffice.social.email.enabled=true",
        "backoffice.social.email.public-base-url=https://localhost",
        "backoffice.social.email.login-path=/login",
        "backoffice.social.email.audience=pulseroom-email"
    }
)
@Import(EmailAuthIntegrationTest.TestEmailAuthConfig.class)
class EmailAuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CapturingEmailAuthMailer mailer;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .build();

        jdbcTemplate.update("delete from email_auth_challenges");
        jdbcTemplate.update("delete from email_accounts");
        jdbcTemplate.update("delete from profile_access_grants");
        jdbcTemplate.update("delete from profile_follows");
        jdbcTemplate.update("delete from post_media_assets");
        jdbcTemplate.update("delete from media_assets");
        jdbcTemplate.update("delete from post_reactions");
        jdbcTemplate.update("delete from posts");
        jdbcTemplate.update("delete from profiles");
        mailer.clear();
    }

    @Test
    void shouldRegisterVerifyAndSignInWithEmailLinks() {
        EmailAuthStartResponse registerResponse = webTestClient.post()
            .uri("/social/v1/auth/email/register")
            .bodyValue(new EmailRegistrationRequest("alice@example.com", "Alice Example"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(EmailAuthStartResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(registerResponse).isNotNull();
        assertThat(registerResponse.message()).contains("verification");

        URI verificationLink = mailer.latestLink();
        URI verificationRedirect = followBackendLink(verificationLink);

        assertThat(queryParam(verificationRedirect, "email_status")).isEqualTo("verified");
        String verifiedToken = queryParam(verificationRedirect, "email_token");
        assertThat(verifiedToken).isNotBlank();

        ViewerResponse viewer = webTestClient.get()
            .uri("/social/v1/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + verifiedToken)
            .exchange()
            .expectStatus().isOk()
            .expectBody(ViewerResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(viewer).isNotNull();
        assertThat(viewer.me().displayName()).isEqualTo("Alice Example");
        assertThat(viewer.me().handle()).isEqualTo("alice");

        mailer.clear();

        EmailAuthStartResponse loginResponse = webTestClient.post()
            .uri("/social/v1/auth/email/login")
            .bodyValue(new EmailLoginRequest("alice@example.com"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(EmailAuthStartResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(loginResponse).isNotNull();
        assertThat(loginResponse.message()).contains("sign-in");

        URI loginLink = mailer.latestLink();
        URI loginRedirect = followBackendLink(loginLink);

        assertThat(queryParam(loginRedirect, "email_status")).isEqualTo("signed-in");
        String loginToken = queryParam(loginRedirect, "email_token");
        assertThat(loginToken).isNotBlank();

        webTestClient.get()
            .uri("/social/v1/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.me.displayName").isEqualTo("Alice Example");
    }

    private URI followBackendLink(URI publicLink) {
        String pathWithQuery = publicLink.getRawPath() + (publicLink.getRawQuery() == null ? "" : "?" + publicLink.getRawQuery());
        String redirectLocation = webTestClient.get()
            .uri(pathWithQuery.replace("/social-api", ""))
            .exchange()
            .expectStatus().is3xxRedirection()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getLocation()
            .toString();

        return URI.create(redirectLocation);
    }

    private String queryParam(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri)
            .build()
            .getQueryParams()
            .getFirst(name);
    }

    @TestConfiguration
    static class TestEmailAuthConfig {
        @Bean
        CapturingEmailAuthMailer capturingEmailAuthMailer() {
            return new CapturingEmailAuthMailer();
        }
    }

    static class CapturingEmailAuthMailer implements EmailAuthMailer {
        private final List<String> htmlBodies = new ArrayList<>();

        @Override
        public void sendHtml(String toEmail, String toName, String subject, String htmlBody) {
            htmlBodies.add(htmlBody);
        }

        URI latestLink() {
            String html = htmlBodies.get(htmlBodies.size() - 1);
            int hrefIndex = html.indexOf("href=\"");
            int start = hrefIndex + 6;
            int end = html.indexOf('"', start);
            return URI.create(html.substring(start, end));
        }

        void clear() {
            htmlBodies.clear();
        }
    }
}

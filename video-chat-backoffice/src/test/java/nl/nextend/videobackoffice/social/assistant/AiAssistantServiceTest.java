package nl.nextend.videobackoffice.social.assistant;

import nl.nextend.videobackoffice.config.BackofficeAiProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.AssistantContextMessage;
import nl.nextend.videobackoffice.social.api.SocialApi.AssistantReplyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

class AiAssistantServiceTest {

    @Test
    void generateReplyShouldCallOpenAiAndReturnAssistantMetadata() {
        BackofficeAiProperties properties = new BackofficeAiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key");
        properties.setAssistantName("Pulse Copilot");
        properties.setModel("gpt-5-mini");

        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                    {
                      "id": "resp_123",
                      "model": "gpt-5-mini",
                      "output_text": "Shared summary"
                    }
                    """)
                .build());
        };

        AiAssistantService service = new AiAssistantService(
            properties,
            WebClient.builder().exchangeFunction(exchangeFunction).build()
        );

        var response = service.generateReply(
            Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("name", "Alice")
                .build(),
            "main-stage",
            new AssistantReplyRequest(
                "Alice",
                "Summarize this room",
                List.of(new AssistantContextMessage("Bob", "Hello", Instant.parse("2026-03-12T19:20:00Z").toString()))
            )
        ).block();

        assertThat(response).isNotNull();
        assertThat(response.agentName()).isEqualTo("Pulse Copilot");
        assertThat(response.reply()).isEqualTo("Shared summary");
        assertThat(response.model()).isEqualTo("gpt-5-mini");
        assertThat(response.responseId()).isEqualTo("resp_123");

        ClientRequest request = capturedRequest.get();
        assertThat(request).isNotNull();
        assertThat(request.url().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
    }

    @Test
    void generateReplyShouldRejectDisabledAssistant() {
        BackofficeAiProperties properties = new BackofficeAiProperties();
        properties.setEnabled(false);

        AiAssistantService service = new AiAssistantService(
            properties,
            WebClient.builder().exchangeFunction(request -> Mono.error(new AssertionError("Should not call OpenAI"))).build()
        );

        Throwable thrown = catchThrowable(() -> service.generateReply(
            Jwt.withTokenValue("token").header("alg", "none").claims(claims -> claims.putAll(Map.of("name", "Alice"))).build(),
            "main-stage",
            new AssistantReplyRequest("Alice", "Ping", List.of())
        ).block());

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown).hasMessageContaining("disabled");
    }
}

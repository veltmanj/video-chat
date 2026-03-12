package nl.nextend.videobackoffice.social;

import nl.nextend.videobackoffice.config.BackofficeAiProperties;
import nl.nextend.videobackoffice.social.SocialApi.AssistantContextMessage;
import nl.nextend.videobackoffice.social.SocialApi.AssistantReplyRequest;
import nl.nextend.videobackoffice.social.SocialApi.AssistantReplyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiAssistantService {

    private static final String OPENAI_RESPONSES_PATH = "https://api.openai.com/v1/responses";

    private final BackofficeAiProperties properties;
    private final WebClient webClient;

    @Autowired
    public AiAssistantService(BackofficeAiProperties properties, WebClient.Builder webClientBuilder) {
        this(properties, webClientBuilder.build());
    }

    AiAssistantService(BackofficeAiProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    public Mono<AssistantReplyResponse> generateReply(Jwt jwt, String roomId, AssistantReplyRequest request) {
        if (!properties.isEnabled()) {
            return Mono.error(new IllegalStateException("The shared AI assistant is disabled."));
        }

        if (properties.getApiKey().isBlank()) {
            return Mono.error(new IllegalStateException("BACKOFFICE_AI_API_KEY is not configured."));
        }

        String participantName = resolveParticipantName(jwt, request.participantName());
        String prompt = normalizePrompt(request.prompt());
        if (prompt.isBlank()) {
            return Mono.error(new IllegalArgumentException("prompt is required."));
        }

        List<AssistantContextMessage> contextMessages = normalizeContext(request.recentMessages());

        return webClient.post()
            .uri(OPENAI_RESPONSES_PATH)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "model", properties.getModel(),
                "input", buildPrompt(roomId, participantName, prompt, contextMessages)
            ))
            .retrieve()
            .onStatus(
                HttpStatusCode::isError,
                response -> response.bodyToMono(OpenAiErrorResponse.class)
                    .defaultIfEmpty(new OpenAiErrorResponse(null))
                    .flatMap(body -> Mono.error(new IllegalStateException(resolveErrorMessage(response.statusCode(), body))))
            )
            .bodyToMono(OpenAiResponse.class)
            .map(this::toAssistantReplyResponse);
    }

    private AssistantReplyResponse toAssistantReplyResponse(OpenAiResponse response) {
        String reply = extractOutputText(response);
        if (reply.isBlank()) {
            throw new IllegalStateException("OpenAI returned an empty response.");
        }

        return new AssistantReplyResponse(
            properties.getAssistantName(),
            reply,
            response == null ? null : response.model(),
            response == null ? null : response.id()
        );
    }

    private String resolveParticipantName(Jwt jwt, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }

        for (String claimName : List.of("name", "given_name", "email", "sub")) {
            Object value = jwt == null ? null : jwt.getClaims().get(claimName);
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return stringValue.trim();
            }
        }

        return "Participant";
    }

    private String normalizePrompt(String prompt) {
        return prompt == null ? "" : prompt.trim();
    }

    private List<AssistantContextMessage> normalizeContext(List<AssistantContextMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
            .filter(Objects::nonNull)
            .filter(message -> message.senderName() != null && !message.senderName().isBlank())
            .filter(message -> message.text() != null && !message.text().isBlank())
            .limit(properties.getMaxContextMessages())
            .toList();
    }

    private String buildPrompt(
        String roomId,
        String participantName,
        String prompt,
        List<AssistantContextMessage> recentMessages
    ) {
        String transcript = recentMessages.isEmpty()
            ? "- No recent room messages were provided."
            : recentMessages.stream()
                .map(message -> "- " + message.senderName().trim()
                    + " [" + formatTimestamp(message.sentAt()) + "]: "
                    + message.text().trim())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- No recent room messages were provided.");

        return String.join("\n",
            "You are " + properties.getAssistantName() + ", a concise AI assistant inside a live video room chat.",
            "Keep replies practical, friendly, and under 120 words unless the user explicitly asks for more detail.",
            "Do not claim you can control cameras, the broker, or account state yourself. Offer steps instead.",
            "",
            "Room ID: " + roomId,
            "Participant: " + participantName,
            "",
            "Recent chat context:",
            transcript,
            "",
            "Latest user request:",
            prompt
        );
    }

    private String formatTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "unknown-time";
        }

        try {
            return Instant.parse(value).toString();
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private String extractOutputText(OpenAiResponse response) {
        if (response != null && response.outputText() != null && !response.outputText().isBlank()) {
            return response.outputText().trim();
        }

        if (response == null || response.output() == null) {
            return "";
        }

        return response.output().stream()
            .filter(Objects::nonNull)
            .flatMap(item -> item.content() == null ? java.util.stream.Stream.empty() : item.content().stream())
            .filter(Objects::nonNull)
            .filter(content -> "output_text".equals(content.type()) || "text".equals(content.type()))
            .map(OpenAiContent::text)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(text -> !text.isBlank())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private String resolveErrorMessage(HttpStatusCode status, OpenAiErrorResponse body) {
        if (body != null && body.error() != null && body.error().message() != null && !body.error().message().isBlank()) {
            return body.error().message().trim();
        }

        return "OpenAI request failed with status " + status.value() + ".";
    }

    record OpenAiResponse(String id, String model, String output_text, List<OpenAiOutput> output) {
        String outputText() {
            return output_text;
        }
    }

    record OpenAiOutput(List<OpenAiContent> content) {
    }

    record OpenAiContent(String type, String text) {
    }

    record OpenAiErrorResponse(OpenAiError error) {
    }

    record OpenAiError(String message) {
    }
}

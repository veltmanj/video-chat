package nl.nextend.videobackoffice.social.assistant;

import jakarta.validation.Valid;
import nl.nextend.videobackoffice.social.api.SocialApi.AssistantReplyRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.AssistantReplyResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Small adapter layer for the room assistant endpoint.
 */
@RestController
@RequestMapping("/social/v1")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    public AiAssistantController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping("/rooms/{roomId}/assistant-replies")
    Mono<AssistantReplyResponse> generateReply(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable String roomId,
                                               @Valid @RequestBody AssistantReplyRequest request) {
        return aiAssistantService.generateReply(jwt, roomId, request);
    }
}

package nl.nextend.videobackoffice.social;

import java.util.List;
import java.util.UUID;

import nl.nextend.videobackoffice.social.SocialApi.CreatePostRequest;
import nl.nextend.videobackoffice.social.SocialApi.FeedResponse;
import nl.nextend.videobackoffice.social.SocialApi.GrantAccessRequest;
import nl.nextend.videobackoffice.social.SocialApi.GrantAccessResult;
import nl.nextend.videobackoffice.social.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.SocialApi.ReactRequest;
import nl.nextend.videobackoffice.social.SocialApi.UpdateProfileRequest;
import nl.nextend.videobackoffice.social.SocialApi.ViewerResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/social/v1")
public class SocialController {

    private final SocialService socialService;

    public SocialController(SocialService socialService) {
        this.socialService = socialService;
    }

    @GetMapping("/me")
    ViewerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return socialService.viewer(jwt);
    }

    @PutMapping("/me")
    ProfileResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody UpdateProfileRequest request) {
        return socialService.updateProfile(jwt, request);
    }

    @GetMapping("/feed")
    FeedResponse feed(@AuthenticationPrincipal Jwt jwt) {
        return socialService.feed(jwt);
    }

    @GetMapping("/profiles/search")
    List<ProfileSummary> searchProfiles(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "all") String scope) {
        return socialService.search(jwt, q, scope);
    }

    @GetMapping("/profiles/{handle}")
    ProfileResponse profile(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return socialService.profile(jwt, handle);
    }

    @PostMapping("/profiles/{handle}/follow")
    ProfileSummary follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return socialService.follow(jwt, handle);
    }

    @DeleteMapping("/profiles/{handle}/follow")
    ProfileSummary unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return socialService.unfollow(jwt, handle);
    }

    @PostMapping("/profiles/{handle}/access-grants")
    GrantAccessResult grantAccess(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable String handle,
                                  @Valid @RequestBody GrantAccessRequest request) {
        return socialService.grantAccess(jwt, handle, request);
    }

    @PostMapping("/posts")
    PostResponse createPost(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePostRequest request) {
        return socialService.createPost(jwt, request);
    }

    @PostMapping("/posts/{postId}/reactions")
    PostResponse addReaction(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable UUID postId,
                             @Valid @RequestBody ReactRequest request) {
        return socialService.addReaction(jwt, postId, request);
    }

    @DeleteMapping("/posts/{postId}/reactions/{reactionType}")
    PostResponse removeReaction(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable UUID postId,
                                @PathVariable String reactionType) {
        return socialService.removeReaction(jwt, postId, reactionType);
    }
}

package nl.nextend.videobackoffice.social.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import nl.nextend.videobackoffice.social.api.SocialApi.CreatePostRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.FeedResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessResult;
import nl.nextend.videobackoffice.social.api.SocialApi.MediaResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.api.SocialApi.ReactRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.UpdateProfileRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.ViewerResponse;
import nl.nextend.videobackoffice.social.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Exposes the main social HTTP endpoints while delegating all business rules to {@link SocialService}.
 */
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

    @PostMapping(path = "/media/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<MediaResponse> uploadMedia(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") FilePart filePart) {
        String contentType = filePart.headers().getContentType() == null ? "" : filePart.headers().getContentType().toString();
        return Mono.usingWhen(
            Mono.fromCallable(() -> Files.createTempFile("social-upload-", ".bin"))
                .subscribeOn(Schedulers.boundedElastic()),
            tempFile -> filePart.transferTo(tempFile)
                .then(Mono.fromCallable(() -> socialService.uploadMedia(jwt, filePart.filename(), contentType, tempFile))
                    .subscribeOn(Schedulers.boundedElastic())),
            SocialController::deleteTempFile
        );
    }

    @GetMapping("/media/{mediaId}/content")
    Mono<ResponseEntity<byte[]>> mediaContent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
        return Mono.fromCallable(() -> socialService.downloadMedia(jwt, mediaId))
            .subscribeOn(Schedulers.boundedElastic())
            .map(media -> ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline().filename(media.fileName()).build().toString()
                )
                .contentType(MediaType.parseMediaType(media.mimeType()))
                .contentLength(media.bytes().length)
                .body(media.bytes()));
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

    private static Mono<Void> deleteTempFile(Path tempFile) {
        return Mono.fromRunnable(() -> {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup for uploaded temp files.
                }
            })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }
}

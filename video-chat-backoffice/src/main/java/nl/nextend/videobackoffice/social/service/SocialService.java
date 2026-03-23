package nl.nextend.videobackoffice.social.service;

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
import nl.nextend.videobackoffice.social.media.MediaContent;
import nl.nextend.videobackoffice.social.media.SocialMediaService;
import nl.nextend.videobackoffice.social.post.SocialPostService;
import nl.nextend.videobackoffice.social.profile.SocialProfileService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public application facade for the social module.
 *
 * <p>The controllers depend on this type only. The implementation deliberately delegates into
 * smaller domain services so HTTP wiring, transaction boundaries, and domain logic stay separated.
 */
@Service
@Transactional
public class SocialService {

    private final SocialProfileService profileService;
    private final SocialPostService postService;
    private final SocialMediaService mediaService;

    public SocialService(SocialProfileService profileService,
                         SocialPostService postService,
                         SocialMediaService mediaService) {
        this.profileService = profileService;
        this.postService = postService;
        this.mediaService = mediaService;
    }

    public ViewerResponse viewer(Jwt jwt) {
        return profileService.viewer(jwt);
    }

    public ProfileResponse updateProfile(Jwt jwt, UpdateProfileRequest request) {
        return profileService.updateProfile(jwt, request);
    }

    public FeedResponse feed(Jwt jwt) {
        return postService.feed(jwt);
    }

    public List<ProfileSummary> search(Jwt jwt, String query, String scope) {
        return profileService.search(jwt, query, scope);
    }

    public ProfileResponse profile(Jwt jwt, String handle) {
        return profileService.profile(jwt, handle);
    }

    public ProfileSummary follow(Jwt jwt, String handle) {
        return profileService.follow(jwt, handle);
    }

    public ProfileSummary unfollow(Jwt jwt, String handle) {
        return profileService.unfollow(jwt, handle);
    }

    public GrantAccessResult grantAccess(Jwt jwt, String handle, GrantAccessRequest request) {
        return profileService.grantAccess(jwt, handle, request);
    }

    public PostResponse createPost(Jwt jwt, CreatePostRequest request) {
        return postService.createPost(jwt, request);
    }

    public MediaResponse uploadMedia(Jwt jwt, String fileName, String contentType, Path tempFile) {
        return mediaService.uploadMedia(jwt, fileName, contentType, tempFile);
    }

    public MediaContent downloadMedia(Jwt jwt, UUID mediaId) {
        return mediaService.downloadMedia(jwt, mediaId);
    }

    public PostResponse addReaction(Jwt jwt, UUID postId, ReactRequest request) {
        return postService.addReaction(jwt, postId, request);
    }

    public PostResponse removeReaction(Jwt jwt, UUID postId, String reactionType) {
        return postService.removeReaction(jwt, postId, reactionType);
    }
}

package nl.nextend.videobackoffice.social.post;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.CreatePostRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.FeedResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ReactRequest;
import nl.nextend.videobackoffice.social.profile.SocialProfileSupport;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.PostRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Owns feed assembly and post mutations.
 *
 * <p>The service validates attachment ownership, reaction constraints, and visibility before
 * mutating persistence state through the shared repository.
 */
@Service
public class SocialPostService {

    private final SocialJdbcRepository repository;
    private final Clock clock;
    private final BackofficeSocialProperties properties;
    private final SocialProfileSupport profiles;
    private final SocialPostHydrator postHydrator;

    SocialPostService(SocialJdbcRepository repository,
                      Clock clock,
                      BackofficeSocialProperties properties,
                      SocialProfileSupport profiles,
                      SocialPostHydrator postHydrator) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.profiles = profiles;
        this.postHydrator = postHydrator;
    }

    public FeedResponse feed(Jwt jwt) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        List<PostResponse> posts = postHydrator.hydratePosts(repository.findFeedPosts(viewer.id(), feedLimit()), viewer.id());
        return new FeedResponse(
            profiles.ownProfileResponse(
                viewer,
                postHydrator.recentPostsForAuthor(viewer.id(), viewer.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
            ),
            posts
        );
    }

    public PostResponse createPost(Jwt jwt, CreatePostRequest request) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        String body = profiles.normalizeOptionalText(request.body());
        List<UUID> mediaIds = normalizeMediaIds(request.mediaIds());
        if (!StringUtils.hasText(body) && mediaIds.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "A post must include text, media, or both.");
        }

        if (!mediaIds.isEmpty()) {
            Map<UUID, MediaAssetRow> ownedMedia = new LinkedHashMap<>();
            for (MediaAssetRow media : repository.findMediaByIdsAndOwner(mediaIds, viewer.id())) {
                ownedMedia.put(media.id(), media);
            }
            if (ownedMedia.size() != mediaIds.size()) {
                throw new ResponseStatusException(BAD_REQUEST, "Posts can only attach your own uploaded media.");
            }
        }

        PostRow created = repository.createPost(UUID.randomUUID(), viewer.id(), body, Instant.now(clock));
        try {
            repository.attachMedia(created.id(), mediaIds);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "One or more media items are already attached to a post.", exception);
        }
        return postHydrator.hydratePosts(List.of(created), viewer.id()).get(0);
    }

    public PostResponse addReaction(Jwt jwt, UUID postId, ReactRequest request) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        String reactionType = normalizeReactionType(request.reactionType());
        assertViewerCanInteractWithPost(viewer.id(), postId);

        repository.addReaction(postId, viewer.id(), reactionType, Instant.now(clock));
        return postHydrator.hydratePosts(List.of(repository.findPostById(postId).orElseThrow()), viewer.id()).get(0);
    }

    public PostResponse removeReaction(Jwt jwt, UUID postId, String reactionType) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        String normalizedReactionType = normalizeReactionType(reactionType);
        assertViewerCanInteractWithPost(viewer.id(), postId);

        repository.removeReaction(postId, viewer.id(), normalizedReactionType);
        return postHydrator.hydratePosts(List.of(repository.findPostById(postId).orElseThrow()), viewer.id()).get(0);
    }

    private void assertViewerCanInteractWithPost(UUID viewerId, UUID postId) {
        PostRow post = repository.findPostById(postId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Post not found."));
        if (viewerId.equals(post.authorProfileId())) {
            return;
        }

        ProfileRow author = profiles.requireProfileById(post.authorProfileId());
        RelationshipSnapshot relationship = profiles.relationship(viewerId, author.id());
        if (!profiles.canView(author, relationship)) {
            throw new ResponseStatusException(FORBIDDEN, "You cannot interact with this post.");
        }
    }

    private List<UUID> normalizeMediaIds(List<String> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }

        List<UUID> normalized = mediaIds.stream()
            .map(candidate -> {
                String value = candidate == null ? "" : candidate.trim();
                if (!StringUtils.hasText(value)) {
                    throw new ResponseStatusException(BAD_REQUEST, "Media ids must not be empty.");
                }
                try {
                    return UUID.fromString(value);
                } catch (IllegalArgumentException exception) {
                    throw new ResponseStatusException(BAD_REQUEST, "Media ids must be valid UUID values.", exception);
                }
            })
            .distinct()
            .toList();

        if (normalized.size() > maxFilesPerPost()) {
            throw new ResponseStatusException(BAD_REQUEST, "Too many media files were attached to this post.");
        }

        return normalized;
    }

    private String normalizeReactionType(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > 32) {
            throw new ResponseStatusException(BAD_REQUEST, "Reaction type must be between 1 and 32 characters.");
        }
        return normalized;
    }

    private int feedLimit() {
        return Math.max(1, Math.min(properties.getFeedLimit(), 200));
    }

    private int maxFilesPerPost() {
        return Math.max(1, Math.min(properties.getMedia().getMaxFilesPerPost(), 8));
    }
}

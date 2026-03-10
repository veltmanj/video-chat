package nl.nextend.videobackoffice.social;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
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
import nl.nextend.videobackoffice.social.SocialJdbcRepository.PostRow;
import nl.nextend.videobackoffice.social.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.SocialJdbcRepository.ProfileSearchRow;
import nl.nextend.videobackoffice.social.SocialJdbcRepository.RelationshipSnapshot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class SocialService {

    private static final int PROFILE_POST_PREVIEW_LIMIT = 20;

    private final SocialJdbcRepository repository;
    private final Clock clock;
    private final BackofficeSocialProperties properties;

    public SocialService(SocialJdbcRepository repository, Clock clock, BackofficeSocialProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
    }

    public ViewerResponse viewer(Jwt jwt) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        return new ViewerResponse(
            ownProfileResponse(viewer),
            repository.findAccessGrantedProfiles(viewer.id()).stream()
                .map(profile -> new ProfileSummary(
                    profile.handle(),
                    profile.displayName(),
                    profile.avatarUrl(),
                    profile.visibility(),
                    true,
                    false,
                    false,
                    false,
                    true
                ))
                .toList()
        );
    }

    public ProfileResponse updateProfile(Jwt jwt, UpdateProfileRequest request) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        ProfileRow updated = repository.updateProfile(
            viewer.id(),
            request.displayName().trim(),
            normalizeOptionalText(request.bio()),
            request.visibility(),
            Instant.now(clock)
        );
        return ownProfileResponse(updated);
    }

    public FeedResponse feed(Jwt jwt) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        List<PostResponse> posts = hydratePosts(repository.findFeedPosts(viewer.id(), feedLimit()), viewer.id());
        return new FeedResponse(ownProfileResponse(viewer), posts);
    }

    public List<ProfileSummary> search(Jwt jwt, String query, String scope) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        boolean mutualOnly = "mutual".equalsIgnoreCase(scope);
        return repository.searchProfiles(viewer.id(), query, mutualOnly, searchLimit()).stream()
            .map(this::toSummary)
            .toList();
    }

    public ProfileResponse profile(Jwt jwt, String handle) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        ProfileRow target = repository.findProfileByHandle(normalizeHandle(handle))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));

        if (viewer.id().equals(target.id())) {
            return ownProfileResponse(target);
        }

        RelationshipSnapshot relationship = repository.relationship(viewer.id(), target.id());
        boolean canView = canView(target, relationship);
        if (!canView) {
            throw new ResponseStatusException(FORBIDDEN, "Profile is private.");
        }

        List<PostResponse> recentPosts = hydratePosts(
            repository.findRecentPostsForAuthor(target.id(), PROFILE_POST_PREVIEW_LIMIT),
            viewer.id()
        );
        return toProfileResponse(target, relationship, recentPosts);
    }

    public ProfileSummary follow(Jwt jwt, String handle) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        ProfileRow target = repository.findProfileByHandle(normalizeHandle(handle))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));
        requireDifferentProfiles(viewer, target);

        repository.follow(viewer.id(), target.id(), Instant.now(clock));
        return toSummary(target, repository.relationship(viewer.id(), target.id()));
    }

    public ProfileSummary unfollow(Jwt jwt, String handle) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        ProfileRow target = repository.findProfileByHandle(normalizeHandle(handle))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));
        requireDifferentProfiles(viewer, target);

        repository.unfollow(viewer.id(), target.id());
        return toSummary(target, repository.relationship(viewer.id(), target.id()));
    }

    public GrantAccessResult grantAccess(Jwt jwt, String handle, GrantAccessRequest request) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        ProfileRow owner = repository.findProfileByHandle(normalizeHandle(handle))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));

        if (!viewer.id().equals(owner.id())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the profile owner can manage access grants.");
        }

        List<String> normalizedHandles = request.viewerHandles().stream()
            .map(this::normalizeHandle)
            .filter(candidate -> !candidate.equals(owner.handle()))
            .distinct()
            .toList();

        List<ProfileRow> matches = repository.findProfilesByHandles(normalizedHandles);
        Map<String, ProfileRow> matchedByHandle = new LinkedHashMap<>();
        for (ProfileRow match : matches) {
            matchedByHandle.put(match.handle(), match);
        }

        List<UUID> viewerIds = matches.stream().map(ProfileRow::id).toList();
        repository.grantAccess(owner.id(), viewerIds, Instant.now(clock));

        List<String> missingHandles = normalizedHandles.stream()
            .filter(candidate -> !matchedByHandle.containsKey(candidate))
            .toList();
        List<String> grantedHandles = matches.stream().map(ProfileRow::handle).toList();

        return new GrantAccessResult(grantedHandles, missingHandles);
    }

    public PostResponse createPost(Jwt jwt, CreatePostRequest request) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        String body = request.body().trim();
        if (!StringUtils.hasText(body)) {
            throw new ResponseStatusException(BAD_REQUEST, "Post body must not be empty.");
        }

        PostRow created = repository.createPost(UUID.randomUUID(), viewer.id(), body, Instant.now(clock));
        return hydratePosts(List.of(created), viewer.id()).get(0);
    }

    public PostResponse addReaction(Jwt jwt, UUID postId, ReactRequest request) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        String reactionType = normalizeReactionType(request.reactionType());
        assertViewerCanInteractWithPost(viewer.id(), postId);

        repository.addReaction(postId, viewer.id(), reactionType, Instant.now(clock));
        return hydratePosts(List.of(repository.findPostById(postId).orElseThrow()), viewer.id()).get(0);
    }

    public PostResponse removeReaction(Jwt jwt, UUID postId, String reactionType) {
        ProfileRow viewer = ensureProfile(AuthUser.fromJwt(jwt));
        String normalizedReactionType = normalizeReactionType(reactionType);
        assertViewerCanInteractWithPost(viewer.id(), postId);

        repository.removeReaction(postId, viewer.id(), normalizedReactionType);
        return hydratePosts(List.of(repository.findPostById(postId).orElseThrow()), viewer.id()).get(0);
    }

    private ProfileRow ensureProfile(AuthUser user) {
        return repository.findProfileBySubject(user.subject())
            .orElseGet(() -> createProfile(user));
    }

    private ProfileRow createProfile(AuthUser user) {
        Instant now = Instant.now(clock);
        String baseHandle = user.suggestedHandleBase();

        for (int attempt = 0; attempt < 24; attempt++) {
            String candidate = withSuffix(baseHandle, attempt);
            try {
                return repository.insertProfile(UUID.randomUUID(), user, candidate, now);
            } catch (DataIntegrityViolationException ignored) {
                // Retry with a new suffix when a concurrent insert won the handle.
            }
        }

        throw new ResponseStatusException(BAD_REQUEST, "Unable to allocate a unique profile handle.");
    }

    private ProfileResponse ownProfileResponse(ProfileRow viewer) {
        RelationshipSnapshot relationship = repository.relationship(viewer.id(), viewer.id());
        List<PostResponse> recentPosts = hydratePosts(
            repository.findRecentPostsForAuthor(viewer.id(), PROFILE_POST_PREVIEW_LIMIT),
            viewer.id()
        );
        return new ProfileResponse(
            viewer.handle(),
            viewer.displayName(),
            viewer.bio(),
            viewer.avatarUrl(),
            viewer.visibility(),
            true,
            false,
            false,
            false,
            false,
            relationship.followerCount(),
            relationship.followingCount(),
            recentPosts
        );
    }

    private ProfileSummary toSummary(ProfileSearchRow row) {
        return new ProfileSummary(
            row.profile().handle(),
            row.profile().displayName(),
            row.profile().avatarUrl(),
            row.profile().visibility(),
            canView(row.profile(), row.following(), row.followsViewer(), row.accessGranted()),
            row.following(),
            row.followsViewer(),
            row.following() && row.followsViewer(),
            row.accessGranted()
        );
    }

    private ProfileSummary toSummary(ProfileRow profile, RelationshipSnapshot relationship) {
        return new ProfileSummary(
            profile.handle(),
            profile.displayName(),
            profile.avatarUrl(),
            profile.visibility(),
            canView(profile, relationship),
            relationship.following(),
            relationship.followsViewer(),
            relationship.following() && relationship.followsViewer(),
            relationship.accessGranted()
        );
    }

    private ProfileResponse toProfileResponse(ProfileRow profile, RelationshipSnapshot relationship, List<PostResponse> recentPosts) {
        return new ProfileResponse(
            profile.handle(),
            profile.displayName(),
            profile.bio(),
            profile.avatarUrl(),
            profile.visibility(),
            canView(profile, relationship),
            relationship.following(),
            relationship.followsViewer(),
            relationship.following() && relationship.followsViewer(),
            relationship.accessGranted(),
            relationship.followerCount(),
            relationship.followingCount(),
            recentPosts
        );
    }

    private List<PostResponse> hydratePosts(List<PostRow> posts, UUID viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<UUID> postIds = posts.stream().map(PostRow::id).toList();
        Map<UUID, Map<String, Integer>> reactionCounts = repository.loadReactionCounts(postIds);
        Map<UUID, Set<String>> viewerReactions = repository.loadViewerReactions(viewerId, postIds);

        List<PostResponse> responses = new ArrayList<>(posts.size());
        for (PostRow post : posts) {
            responses.add(new PostResponse(
                post.id().toString(),
                post.authorHandle(),
                post.authorDisplayName(),
                post.authorAvatarUrl(),
                post.body(),
                post.createdAt(),
                reactionCounts.getOrDefault(post.id(), Map.of()),
                viewerReactions.getOrDefault(post.id(), Set.of())
            ));
        }
        return responses;
    }

    private void assertViewerCanInteractWithPost(UUID viewerId, UUID postId) {
        PostRow post = repository.findPostById(postId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Post not found."));
        if (viewerId.equals(post.authorProfileId())) {
            return;
        }

        ProfileRow author = repository.findProfileById(post.authorProfileId()).orElseThrow();
        RelationshipSnapshot relationship = repository.relationship(viewerId, author.id());
        if (!canView(author, relationship)) {
            throw new ResponseStatusException(FORBIDDEN, "You cannot interact with this post.");
        }
    }

    private boolean canView(ProfileRow target, RelationshipSnapshot relationship) {
        return canView(target, relationship.following(), relationship.followsViewer(), relationship.accessGranted());
    }

    private boolean canView(ProfileRow target, boolean following, boolean followsViewer, boolean accessGranted) {
        if (target.visibility() == ProfileVisibility.PUBLIC) {
            return true;
        }

        return accessGranted || (following && followsViewer);
    }

    private void requireDifferentProfiles(ProfileRow viewer, ProfileRow target) {
        if (viewer.id().equals(target.id())) {
            throw new ResponseStatusException(BAD_REQUEST, "A profile cannot follow itself.");
        }
    }

    private String normalizeHandle(String handle) {
        if (!StringUtils.hasText(handle)) {
            throw new ResponseStatusException(BAD_REQUEST, "Profile handle must not be empty.");
        }

        return handle.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeReactionType(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > 32) {
            throw new ResponseStatusException(BAD_REQUEST, "Reaction type must be between 1 and 32 characters.");
        }
        return normalized;
    }

    private String withSuffix(String baseHandle, int attempt) {
        if (attempt == 0) {
            return baseHandle;
        }

        String suffix = "-" + attempt;
        int maxBaseLength = Math.max(1, 32 - suffix.length());
        String truncatedBase = baseHandle.substring(0, Math.min(baseHandle.length(), maxBaseLength));
        return truncatedBase + suffix;
    }

    private int feedLimit() {
        return Math.max(1, Math.min(properties.getFeedLimit(), 200));
    }

    private int searchLimit() {
        return Math.max(1, Math.min(properties.getSearchLimit(), 100));
    }
}

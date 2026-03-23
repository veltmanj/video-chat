package nl.nextend.videobackoffice.social.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import nl.nextend.videobackoffice.social.api.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileSearchRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Shared profile rules used by multiple social subdomains.
 *
 * <p>This class centralizes profile bootstrap, handle normalization, visibility checks, and mapping
 * to API shapes so those rules stay consistent across the feed, profile, and media flows.
 */
@Service
public class SocialProfileSupport {

    public static final int PROFILE_POST_PREVIEW_LIMIT = 20;

    private final SocialJdbcRepository repository;
    private final Clock clock;

    SocialProfileSupport(SocialJdbcRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ProfileRow ensureProfile(Jwt jwt) {
        return ensureProfile(AuthUser.fromJwt(jwt));
    }

    public ProfileRow ensureProfile(AuthUser user) {
        return repository.findProfileBySubject(user.subject())
            .orElseGet(() -> createProfile(user));
    }

    public ProfileRow requireProfileByHandle(String handle) {
        return repository.findProfileByHandle(normalizeHandle(handle))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));
    }

    public ProfileRow requireProfileById(UUID profileId) {
        return repository.findProfileById(profileId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Profile not found."));
    }

    public RelationshipSnapshot relationship(UUID viewerId, UUID targetId) {
        return repository.relationship(viewerId, targetId);
    }

    public ProfileResponse ownProfileResponse(ProfileRow viewer, List<PostResponse> recentPosts) {
        RelationshipSnapshot relationship = repository.relationship(viewer.id(), viewer.id());
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

    public ProfileSummary toSummary(ProfileSearchRow row) {
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

    public ProfileSummary toSummary(ProfileRow profile, RelationshipSnapshot relationship) {
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

    public ProfileResponse toProfileResponse(ProfileRow profile, RelationshipSnapshot relationship, List<PostResponse> recentPosts) {
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

    public boolean canView(ProfileRow target, RelationshipSnapshot relationship) {
        return canView(target, relationship.following(), relationship.followsViewer(), relationship.accessGranted());
    }

    public boolean canView(ProfileRow target, boolean following, boolean followsViewer, boolean accessGranted) {
        if (target.visibility() == ProfileVisibility.PUBLIC) {
            return true;
        }

        return accessGranted || (following && followsViewer);
    }

    public void requireDifferentProfiles(ProfileRow viewer, ProfileRow target) {
        if (viewer.id().equals(target.id())) {
            throw new ResponseStatusException(BAD_REQUEST, "A profile cannot follow itself.");
        }
    }

    public String normalizeHandle(String handle) {
        if (!StringUtils.hasText(handle)) {
            throw new ResponseStatusException(BAD_REQUEST, "Profile handle must not be empty.");
        }

        return handle.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
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

    private String withSuffix(String baseHandle, int attempt) {
        if (attempt == 0) {
            return baseHandle;
        }

        String suffix = "-" + attempt;
        int maxBaseLength = Math.max(1, 32 - suffix.length());
        String truncatedBase = baseHandle.substring(0, Math.min(baseHandle.length(), maxBaseLength));
        return truncatedBase + suffix;
    }
}

package nl.nextend.videobackoffice.social.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessResult;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.api.SocialApi.UpdateProfileRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.ViewerResponse;
import nl.nextend.videobackoffice.social.post.SocialPostHydrator;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Handles profile-centric use cases such as profile lookup, updates, follows, and manual access
 * grants for private profiles.
 */
@Service
public class SocialProfileService {

    private final SocialJdbcRepository repository;
    private final Clock clock;
    private final BackofficeSocialProperties properties;
    private final SocialProfileSupport profiles;
    private final SocialPostHydrator postHydrator;

    SocialProfileService(SocialJdbcRepository repository,
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

    public ViewerResponse viewer(Jwt jwt) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        return new ViewerResponse(
            profiles.ownProfileResponse(
                viewer,
                postHydrator.recentPostsForAuthor(viewer.id(), viewer.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
            ),
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
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow updated = repository.updateProfile(
            viewer.id(),
            request.displayName().trim(),
            profiles.normalizeOptionalText(request.bio()),
            request.visibility(),
            Instant.now(clock)
        );
        return profiles.ownProfileResponse(
            updated,
            postHydrator.recentPostsForAuthor(updated.id(), updated.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
        );
    }

    public List<ProfileSummary> search(Jwt jwt, String query, String scope) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        boolean mutualOnly = "mutual".equalsIgnoreCase(scope);
        return repository.searchProfiles(viewer.id(), query, mutualOnly, searchLimit()).stream()
            .map(profiles::toSummary)
            .toList();
    }

    public ProfileResponse profile(Jwt jwt, String handle) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow target = profiles.requireProfileByHandle(handle);

        if (viewer.id().equals(target.id())) {
            return profiles.ownProfileResponse(
                target,
                postHydrator.recentPostsForAuthor(target.id(), viewer.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
            );
        }

        RelationshipSnapshot relationship = profiles.relationship(viewer.id(), target.id());
        if (!profiles.canView(target, relationship)) {
            throw new ResponseStatusException(FORBIDDEN, "Profile is private.");
        }

        return profiles.toProfileResponse(
            target,
            relationship,
            postHydrator.recentPostsForAuthor(target.id(), viewer.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
        );
    }

    public ProfileSummary follow(Jwt jwt, String handle) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow target = profiles.requireProfileByHandle(handle);
        profiles.requireDifferentProfiles(viewer, target);

        repository.follow(viewer.id(), target.id(), Instant.now(clock));
        return profiles.toSummary(target, profiles.relationship(viewer.id(), target.id()));
    }

    public ProfileSummary unfollow(Jwt jwt, String handle) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow target = profiles.requireProfileByHandle(handle);
        profiles.requireDifferentProfiles(viewer, target);

        repository.unfollow(viewer.id(), target.id());
        return profiles.toSummary(target, profiles.relationship(viewer.id(), target.id()));
    }

    public GrantAccessResult grantAccess(Jwt jwt, String handle, GrantAccessRequest request) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow owner = profiles.requireProfileByHandle(handle);

        if (!viewer.id().equals(owner.id())) {
            throw new ResponseStatusException(FORBIDDEN, "Only the profile owner can manage access grants.");
        }

        List<String> normalizedHandles = request.viewerHandles().stream()
            .map(profiles::normalizeHandle)
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

    private int searchLimit() {
        return Math.max(1, Math.min(properties.getSearchLimit(), 100));
    }
}

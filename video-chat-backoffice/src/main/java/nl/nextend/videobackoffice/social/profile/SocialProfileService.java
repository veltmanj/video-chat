package nl.nextend.videobackoffice.social.profile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.GrantAccessResult;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.api.SocialApi.UpdateProfileRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.ViewerResponse;
import nl.nextend.videobackoffice.social.media.MediaContent;
import nl.nextend.videobackoffice.social.media.SocialMediaStorage;
import nl.nextend.videobackoffice.social.post.SocialPostHydrator;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

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
    private final SocialMediaStorage mediaStorage;

    SocialProfileService(SocialJdbcRepository repository,
                         Clock clock,
                         BackofficeSocialProperties properties,
                         SocialProfileSupport profiles,
                         SocialPostHydrator postHydrator,
                         ObjectProvider<SocialMediaStorage> mediaStorageProvider) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.profiles = profiles;
        this.postHydrator = postHydrator;
        this.mediaStorage = mediaStorageProvider.getIfAvailable();
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

    public ProfileResponse uploadAvatar(Jwt jwt, String fileName, String contentType, Path tempFile) {
        requireAvatarStorageEnabled();

        ProfileRow viewer = profiles.ensureProfile(jwt);
        String normalizedContentType = normalizeAvatarContentType(contentType);
        long fileSize = fileSize(tempFile);
        if (fileSize < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded avatar must not be empty.");
        }
        if (fileSize > properties.getMedia().getMaxUploadBytes()) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded avatar exceeds the configured size limit.");
        }

        String normalizedFileName = normalizeFileName(fileName);
        String objectKey = buildAvatarStorageKey(viewer.id(), normalizedFileName);
        mediaStorage.putObject(objectKey, tempFile, normalizedContentType);

        ProfileRow updated = repository.updateProfileAvatar(
            viewer.id(),
            profileAvatarUrl(viewer.handle()),
            objectKey,
            normalizedContentType,
            Instant.now(clock)
        );
        return profiles.ownProfileResponse(
            updated,
            postHydrator.recentPostsForAuthor(updated.id(), updated.id(), SocialProfileSupport.PROFILE_POST_PREVIEW_LIMIT)
        );
    }

    public ProfileResponse clearAvatar(Jwt jwt) {
        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow updated = repository.clearProfileAvatar(viewer.id(), Instant.now(clock));
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

    public MediaContent downloadAvatar(Jwt jwt, String handle) {
        requireAvatarStorageEnabled();

        ProfileRow viewer = profiles.ensureProfile(jwt);
        ProfileRow target = profiles.requireProfileByHandle(handle);

        if (!StringUtils.hasText(target.avatarStorageKey()) || !StringUtils.hasText(target.avatarContentType())) {
            throw new ResponseStatusException(NOT_FOUND, "Avatar not found.");
        }

        if (!viewer.id().equals(target.id())) {
            RelationshipSnapshot relationship = profiles.relationship(viewer.id(), target.id());
            if (!profiles.canView(target, relationship)) {
                throw new ResponseStatusException(FORBIDDEN, "Profile is private.");
            }
        }

        byte[] bytes = mediaStorage.getObject(target.avatarStorageKey());
        return new MediaContent(
            avatarFileName(target.handle(), target.avatarContentType()),
            target.avatarContentType(),
            bytes
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

    private void requireAvatarStorageEnabled() {
        if (!properties.getMedia().isEnabled() || mediaStorage == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Media storage is not configured.");
        }
    }

    private String normalizeAvatarContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalized) || !normalized.startsWith("image/")) {
            throw new ResponseStatusException(BAD_REQUEST, "Only image uploads are supported for avatars.");
        }
        return normalized;
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to inspect the uploaded avatar.", exception);
        }
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        String baseName = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        if (!StringUtils.hasText(baseName)) {
            baseName = "avatar";
        }

        String cleaned = baseName.replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.length() <= 255) {
            return cleaned;
        }
        return cleaned.substring(0, 255);
    }

    private String buildAvatarStorageKey(UUID profileId, String fileName) {
        return "profiles/" + profileId + "/avatar" + fileExtension(fileName);
    }

    private String fileExtension(String fileName) {
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex > -1 && extensionIndex < fileName.length() - 1) {
            String suffix = fileName.substring(extensionIndex).toLowerCase(Locale.ROOT);
            if (suffix.length() <= 16) {
                return suffix;
            }
        }
        return "";
    }

    private String fileExtensionFromMimeType(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "image/bmp" -> ".bmp";
            default -> "";
        };
    }

    private String avatarFileName(String handle, String contentType) {
        return handle + fileExtensionFromMimeType(contentType);
    }

    private String profileAvatarUrl(String handle) {
        return "/social-api/social/v1/profiles/" + handle + "/avatar";
    }
}

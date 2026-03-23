package nl.nextend.videobackoffice.social.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.MediaResponse;
import nl.nextend.videobackoffice.social.profile.SocialProfileSupport;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAccessRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
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
 * Applies media-specific policy before delegating to storage and persistence.
 *
 * <p>Upload size limits, content type validation, object key generation, and authorization checks
 * for media downloads all live here so they do not leak into controllers.
 */
@Service
public class SocialMediaService {

    private final SocialJdbcRepository repository;
    private final Clock clock;
    private final BackofficeSocialProperties properties;
    private final SocialProfileSupport profiles;
    private final SocialMediaStorage mediaStorage;

    SocialMediaService(SocialJdbcRepository repository,
                       Clock clock,
                       BackofficeSocialProperties properties,
                       SocialProfileSupport profiles,
                       ObjectProvider<SocialMediaStorage> mediaStorageProvider) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.profiles = profiles;
        this.mediaStorage = mediaStorageProvider.getIfAvailable();
    }

    public MediaResponse uploadMedia(Jwt jwt, String fileName, String contentType, Path tempFile) {
        requireMediaEnabled();

        ProfileRow viewer = profiles.ensureProfile(jwt);
        String normalizedContentType = normalizeContentType(contentType);
        MediaKind kind = resolveMediaKind(normalizedContentType);
        long fileSize = fileSize(tempFile);
        if (fileSize < 1) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded media must not be empty.");
        }
        if (fileSize > properties.getMedia().getMaxUploadBytes()) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded media exceeds the configured size limit.");
        }

        UUID mediaId = UUID.randomUUID();
        String normalizedFileName = normalizeFileName(fileName);
        String objectKey = buildStorageKey(viewer.id(), mediaId, normalizedFileName);

        mediaStorage.putObject(objectKey, tempFile, normalizedContentType);
        MediaAssetRow created = repository.createMedia(
            mediaId,
            viewer.id(),
            objectKey,
            normalizedFileName,
            normalizedContentType,
            kind,
            fileSize,
            Instant.now(clock)
        );
        return toMediaResponse(created);
    }

    public MediaContent downloadMedia(Jwt jwt, UUID mediaId) {
        requireMediaEnabled();

        ProfileRow viewer = profiles.ensureProfile(jwt);
        MediaAccessRow media = repository.findMediaAccessById(mediaId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Media not found."));

        if (media.postId() == null) {
            if (!viewer.id().equals(media.media().ownerProfileId())) {
                throw new ResponseStatusException(FORBIDDEN, "You cannot access this media.");
            }
        } else if (!viewer.id().equals(media.authorProfileId())) {
            ProfileRow author = profiles.requireProfileById(media.authorProfileId());
            RelationshipSnapshot relationship = profiles.relationship(viewer.id(), author.id());
            if (!profiles.canView(author, relationship)) {
                throw new ResponseStatusException(FORBIDDEN, "You cannot access this media.");
            }
        }

        byte[] bytes = mediaStorage.getObject(media.media().storageKey());
        return new MediaContent(
            media.media().originalFilename(),
            media.media().mimeType(),
            bytes
        );
    }

    private void requireMediaEnabled() {
        if (!properties.getMedia().isEnabled() || mediaStorage == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Media storage is not configured.");
        }
    }

    private String normalizeContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Uploaded media must include a content type.");
        }
        return normalized;
    }

    private MediaKind resolveMediaKind(String contentType) {
        if (contentType.startsWith("image/")) {
            return MediaKind.IMAGE;
        }
        if (contentType.startsWith("video/")) {
            return MediaKind.VIDEO;
        }
        throw new ResponseStatusException(BAD_REQUEST, "Only image and video uploads are supported.");
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to inspect the uploaded media.", exception);
        }
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        String baseName = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        if (!StringUtils.hasText(baseName)) {
            baseName = "upload";
        }

        String cleaned = baseName.replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.length() <= 255) {
            return cleaned;
        }
        return cleaned.substring(0, 255);
    }

    private String buildStorageKey(UUID ownerProfileId, UUID mediaId, String fileName) {
        String extension = "";
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex > -1 && extensionIndex < fileName.length() - 1) {
            String suffix = fileName.substring(extensionIndex).toLowerCase(Locale.ROOT);
            if (suffix.length() <= 16) {
                extension = suffix;
            }
        }
        return "profiles/" + ownerProfileId + "/" + mediaId + extension;
    }

    private MediaResponse toMediaResponse(MediaAssetRow media) {
        return new MediaResponse(
            media.id().toString(),
            media.kind(),
            media.mimeType(),
            media.originalFilename(),
            media.fileSize()
        );
    }
}

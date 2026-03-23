package nl.nextend.videobackoffice.social.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.profile.ProfileVisibility;
import nl.nextend.videobackoffice.social.profile.SocialProfileSupport;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAccessRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialMediaServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadMediaStoresValidatedFiles() throws Exception {
        SocialJdbcRepository repository = mock(SocialJdbcRepository.class);
        SocialProfileSupport profiles = mock(SocialProfileSupport.class);
        SocialMediaStorage storage = mock(SocialMediaStorage.class);
        ObjectProvider<SocialMediaStorage> provider = provider(storage);
        Clock clock = Clock.fixed(Instant.parse("2026-03-23T12:00:00Z"), ZoneOffset.UTC);
        BackofficeSocialProperties properties = new BackofficeSocialProperties();
        properties.getMedia().setEnabled(true);
        properties.getMedia().setMaxUploadBytes(64);
        SocialMediaService service = new SocialMediaService(repository, clock, properties, profiles, provider);
        ProfileRow viewer = profile("viewer", ProfileVisibility.PUBLIC);
        Path file = tempDir.resolve("hello.png");
        Files.write(file, new byte[] {1, 2, 3, 4});
        MediaAssetRow created = new MediaAssetRow(UUID.randomUUID(), viewer.id(), "profiles/viewer/object.png", "hello.png", "image/png", MediaKind.IMAGE, 4L, Instant.now(clock));

        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(repository.createMedia(any(), eq(viewer.id()), any(), eq("hello.png"), eq("image/png"), eq(MediaKind.IMAGE), eq(4L), eq(Instant.now(clock))))
            .thenReturn(created);

        var response = service.uploadMedia(jwt(), "hello.png", "image/png", file);

        assertThat(response.fileName()).isEqualTo("hello.png");
        assertThat(response.kind()).isEqualTo(MediaKind.IMAGE);
        verify(storage).putObject(any(), eq(file), eq("image/png"));
    }

    @Test
    void downloadMediaRejectsOrphanedFilesOwnedByAnotherProfile() {
        SocialJdbcRepository repository = mock(SocialJdbcRepository.class);
        SocialProfileSupport profiles = mock(SocialProfileSupport.class);
        SocialMediaStorage storage = mock(SocialMediaStorage.class);
        BackofficeSocialProperties properties = new BackofficeSocialProperties();
        properties.getMedia().setEnabled(true);
        SocialMediaService service = new SocialMediaService(repository, Clock.systemUTC(), properties, profiles, provider(storage));
        ProfileRow viewer = profile("viewer", ProfileVisibility.PUBLIC);
        ProfileRow owner = profile("owner", ProfileVisibility.PUBLIC);
        MediaAssetRow media = new MediaAssetRow(UUID.randomUUID(), owner.id(), "profiles/owner/object.png", "hello.png", "image/png", MediaKind.IMAGE, 4L, Instant.now());

        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(repository.findMediaAccessById(media.id())).thenReturn(Optional.of(new MediaAccessRow(media, null, null)));

        assertThatThrownBy(() -> service.downloadMedia(jwt(), media.id()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("cannot access");
    }

    private static ObjectProvider<SocialMediaStorage> provider(SocialMediaStorage storage) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SocialMediaStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        return provider;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").subject("viewer-subject").build();
    }

    private static ProfileRow profile(String handle, ProfileVisibility visibility) {
        return new ProfileRow(
            UUID.randomUUID(),
            handle + "-subject",
            handle + "@example.com",
            handle,
            handle,
            "",
            "",
            visibility,
            Instant.parse("2026-03-23T12:00:00Z"),
            Instant.parse("2026-03-23T12:00:00Z")
        );
    }
}

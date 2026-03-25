package nl.nextend.videobackoffice.social.post;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.CreatePostRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.ReactRequest;
import nl.nextend.videobackoffice.social.media.MediaKind;
import nl.nextend.videobackoffice.social.profile.ProfileVisibility;
import nl.nextend.videobackoffice.social.profile.SocialProfileSupport;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.PostRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialPostServiceTest {

    @Test
    void createPostRejectsRequestsWithoutBodyAndMedia() {
        SocialPostService service = newService();
        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(profiles.normalizeOptionalText("   ")).thenReturn("");

        assertThatThrownBy(() -> service.createPost(jwt(), new CreatePostRequest("   ", List.of())))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("must include text, media, or both");
    }

    @Test
    void createPostRejectsMediaNotOwnedByTheViewer() {
        SocialPostService service = newService();
        String mediaId = UUID.randomUUID().toString();
        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(profiles.normalizeOptionalText("")).thenReturn("");
        when(repository.findMediaByIdsAndOwner(List.of(UUID.fromString(mediaId)), viewer.id())).thenReturn(List.of());

        assertThatThrownBy(() -> service.createPost(jwt(), new CreatePostRequest("", List.of(mediaId))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("own uploaded media");
    }

    @Test
    void createPostPersistsAndHydratesValidPosts() {
        SocialPostService service = newService();
        String mediaId = UUID.randomUUID().toString();
        UUID parsedMediaId = UUID.fromString(mediaId);
        PostRow created = new PostRow(UUID.randomUUID(), viewer.id(), "hello", Instant.now(clock), viewer.handle(), viewer.displayName(), viewer.avatarUrl());
        PostResponse hydrated = new PostResponse(created.id().toString(), viewer.handle(), viewer.displayName(), viewer.avatarUrl(), "hello", created.createdAt(), List.of(), java.util.Map.of(), java.util.Set.of());
        MediaAssetRow ownedMedia = new MediaAssetRow(parsedMediaId, viewer.id(), "profiles/viewer/object", "hello.png", "image/png", MediaKind.IMAGE, 4L, Instant.now(clock));

        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(profiles.normalizeOptionalText("hello")).thenReturn("hello");
        when(repository.findMediaByIdsAndOwner(List.of(parsedMediaId), viewer.id())).thenReturn(List.of(ownedMedia));
        when(repository.createPost(any(), eq(viewer.id()), eq("hello"), eq(Instant.now(clock)))).thenReturn(created);
        when(postHydrator.hydratePosts(List.of(created), viewer.id())).thenReturn(List.of(hydrated));

        PostResponse response = service.createPost(jwt(), new CreatePostRequest("hello", List.of(mediaId)));

        assertThat(response).isEqualTo(hydrated);
        verify(repository).attachMedia(created.id(), List.of(parsedMediaId));
    }

    @Test
    void addReactionRejectsPostsTheViewerCannotSee() {
        SocialPostService service = newService();
        ProfileRow author = profile("author", ProfileVisibility.PRIVATE);
        PostRow post = new PostRow(UUID.randomUUID(), author.id(), "hidden", Instant.now(clock), author.handle(), author.displayName(), author.avatarUrl());
        RelationshipSnapshot relationship = new RelationshipSnapshot(false, false, false, 0, 0);

        when(profiles.ensureProfile(any(Jwt.class))).thenReturn(viewer);
        when(repository.findPostById(post.id())).thenReturn(Optional.of(post));
        when(profiles.requireProfileById(author.id())).thenReturn(author);
        when(profiles.relationship(viewer.id(), author.id())).thenReturn(relationship);
        when(profiles.canView(author, relationship)).thenReturn(false);

        assertThatThrownBy(() -> service.addReaction(jwt(), post.id(), new ReactRequest("fire")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("cannot interact");
    }

    private final SocialJdbcRepository repository = mock(SocialJdbcRepository.class);
    private final SocialProfileSupport profiles = mock(SocialProfileSupport.class);
    private final SocialPostHydrator postHydrator = mock(SocialPostHydrator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-23T12:00:00Z"), ZoneOffset.UTC);
    private final ProfileRow viewer = profile("viewer", ProfileVisibility.PUBLIC);

    private SocialPostService newService() {
        BackofficeSocialProperties properties = new BackofficeSocialProperties();
        properties.getMedia().setMaxFilesPerPost(4);
        properties.setFeedLimit(50);
        return new SocialPostService(repository, clock, properties, profiles, postHydrator);
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
            "",
            "",
            visibility,
            Instant.parse("2026-03-23T12:00:00Z"),
            Instant.parse("2026-03-23T12:00:00Z")
        );
    }
}

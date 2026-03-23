package nl.nextend.videobackoffice.social.post;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nl.nextend.videobackoffice.social.media.MediaKind;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.PostRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialPostHydratorTest {

    @Test
    void hydratePostsAddsMediaReactionCountsAndViewerReactions() {
        SocialJdbcRepository repository = mock(SocialJdbcRepository.class);
        SocialPostHydrator hydrator = new SocialPostHydrator(repository);
        UUID viewerId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        PostRow post = new PostRow(postId, UUID.randomUUID(), "hello", Instant.parse("2026-03-23T12:00:00Z"), "alice", "Alice", null);
        MediaAssetRow media = new MediaAssetRow(
            UUID.randomUUID(),
            post.authorProfileId(),
            "profiles/alice/object",
            "hello.png",
            "image/png",
            MediaKind.IMAGE,
            12L,
            Instant.parse("2026-03-23T12:00:00Z")
        );

        when(repository.loadMediaForPosts(List.of(postId))).thenReturn(Map.of(postId, List.of(media)));
        when(repository.loadReactionCounts(List.of(postId))).thenReturn(Map.of(postId, Map.of("fire", 2)));
        when(repository.loadViewerReactions(viewerId, List.of(postId))).thenReturn(Map.of(postId, Set.of("fire")));

        var responses = hydrator.hydratePosts(List.of(post), viewerId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).media()).hasSize(1);
        assertThat(responses.get(0).media().get(0).fileName()).isEqualTo("hello.png");
        assertThat(responses.get(0).reactionCounts()).containsEntry("fire", 2);
        assertThat(responses.get(0).viewerReactions()).containsExactly("fire");
    }
}

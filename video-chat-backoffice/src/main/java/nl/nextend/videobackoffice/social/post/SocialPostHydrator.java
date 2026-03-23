package nl.nextend.videobackoffice.social.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nl.nextend.videobackoffice.social.api.SocialApi.MediaResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.MediaAssetRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.PostRow;
import org.springframework.stereotype.Service;

/**
 * Enriches raw post rows with media and reaction aggregates in batched repository calls.
 *
 * <p>This keeps the mutation services focused on authorization and validation while a single place
 * handles the response assembly strategy.
 */
@Service
public class SocialPostHydrator {

    private final SocialJdbcRepository repository;

    SocialPostHydrator(SocialJdbcRepository repository) {
        this.repository = repository;
    }

    public List<PostResponse> recentPostsForAuthor(UUID authorId, UUID viewerId, int limit) {
        return hydratePosts(repository.findRecentPostsForAuthor(authorId, limit), viewerId);
    }

    public List<PostResponse> hydratePosts(List<PostRow> posts, UUID viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<UUID> postIds = posts.stream().map(PostRow::id).toList();
        Map<UUID, List<MediaAssetRow>> mediaByPost = repository.loadMediaForPosts(postIds);
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
                mediaByPost.getOrDefault(post.id(), List.of()).stream().map(this::toMediaResponse).toList(),
                reactionCounts.getOrDefault(post.id(), Map.of()),
                viewerReactions.getOrDefault(post.id(), Set.of())
            ));
        }
        return responses;
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

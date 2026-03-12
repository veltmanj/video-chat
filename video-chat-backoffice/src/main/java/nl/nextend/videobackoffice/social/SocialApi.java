package nl.nextend.videobackoffice.social;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SocialApi {

    private SocialApi() {
    }

    public record ViewerResponse(ProfileResponse me, List<ProfileSummary> accessGrants) {
    }

    public record FeedResponse(ProfileResponse me, List<PostResponse> posts) {
    }

    public record ProfileResponse(
        String handle,
        String displayName,
        String bio,
        String avatarUrl,
        ProfileVisibility visibility,
        boolean canView,
        boolean following,
        boolean followsViewer,
        boolean mutualConnection,
        boolean accessGranted,
        int followerCount,
        int followingCount,
        List<PostResponse> recentPosts
    ) {
    }

    public record ProfileSummary(
        String handle,
        String displayName,
        String avatarUrl,
        ProfileVisibility visibility,
        boolean canView,
        boolean following,
        boolean followsViewer,
        boolean mutualConnection,
        boolean accessGranted
    ) {
    }

    public record PostResponse(
        String id,
        String authorHandle,
        String authorDisplayName,
        String authorAvatarUrl,
        String body,
        Instant createdAt,
        List<MediaResponse> media,
        Map<String, Integer> reactionCounts,
        Set<String> viewerReactions
    ) {
    }

    public record MediaResponse(
        String id,
        MediaKind kind,
        String mimeType,
        String fileName,
        long fileSize
    ) {
    }

    public record UpdateProfileRequest(
        @NotBlank @Size(max = 80) String displayName,
        @Size(max = 400) String bio,
        @NotNull ProfileVisibility visibility
    ) {
    }

    public record CreatePostRequest(@Size(max = 2000) String body, List<String> mediaIds) {
    }

    public record ReactRequest(@NotBlank @Size(max = 32) String reactionType) {
    }

    public record GrantAccessRequest(@NotEmpty List<@NotBlank String> viewerHandles) {
    }

    public record GrantAccessResult(List<String> grantedHandles, List<String> missingHandles) {
    }

    public record AssistantContextMessage(
        @NotBlank @Size(max = 120) String senderName,
        @NotBlank @Size(max = 2000) String text,
        String sentAt
    ) {
    }

    public record AssistantReplyRequest(
        @Size(max = 120) String participantName,
        @NotBlank @Size(max = 2000) String prompt,
        List<@NotNull AssistantContextMessage> recentMessages
    ) {
    }

    public record AssistantReplyResponse(
        String agentName,
        String reply,
        String model,
        String responseId
    ) {
    }
}

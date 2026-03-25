package nl.nextend.videobackoffice.social.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import nl.nextend.videobackoffice.social.media.MediaKind;
import nl.nextend.videobackoffice.social.profile.AuthUser;
import nl.nextend.videobackoffice.social.profile.ProfileVisibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed persistence gateway for the social domain.
 *
 * <p>The repository intentionally returns small immutable row projections rather than entity graphs.
 * That keeps query shapes explicit and allows the higher layers to batch-hydrate API responses
 * without an ORM.
 */
@Repository
public class SocialJdbcRepository {

    private static final RowMapper<ProfileRow> PROFILE_ROW_MAPPER = (rs, rowNum) -> new ProfileRow(
        rs.getObject("id", UUID.class),
        rs.getString("subject"),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getString("handle"),
        rs.getString("avatar_url"),
        rs.getString("avatar_storage_key"),
        rs.getString("avatar_content_type"),
        rs.getString("bio"),
        ProfileVisibility.valueOf(rs.getString("visibility")),
        timestamp(rs, "created_at"),
        timestamp(rs, "updated_at")
    );

    private static final RowMapper<PostRow> POST_ROW_MAPPER = (rs, rowNum) -> new PostRow(
        rs.getObject("post_id", UUID.class),
        rs.getObject("author_profile_id", UUID.class),
        rs.getString("body"),
        timestamp(rs, "created_at"),
        rs.getString("author_handle"),
        rs.getString("author_display_name"),
        rs.getString("author_avatar_url")
    );

    private static final RowMapper<MediaAssetRow> MEDIA_ROW_MAPPER = (rs, rowNum) -> new MediaAssetRow(
        rs.getObject("media_id", UUID.class),
        rs.getObject("owner_profile_id", UUID.class),
        rs.getString("storage_key"),
        rs.getString("original_filename"),
        rs.getString("mime_type"),
        MediaKind.valueOf(rs.getString("media_kind")),
        rs.getLong("file_size"),
        timestamp(rs, "media_created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    SocialJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProfileRow> findProfileBySubject(String subject) {
        return jdbcTemplate.query(
            "select * from profiles where subject = ?",
            PROFILE_ROW_MAPPER,
            subject
        ).stream().findFirst();
    }

    public Optional<ProfileRow> findProfileByHandle(String handle) {
        return jdbcTemplate.query(
            "select * from profiles where handle = ?",
            PROFILE_ROW_MAPPER,
            handle
        ).stream().findFirst();
    }

    public boolean existsHandle(String handle) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from profiles where handle = ?",
            Integer.class,
            handle
        );
        return count != null && count > 0;
    }

    public ProfileRow insertProfile(UUID id, AuthUser user, String handle, Instant now) {
        jdbcTemplate.update(
            """
            insert into profiles (
                id, subject, email, display_name, handle, avatar_url, avatar_storage_key, avatar_content_type, bio, visibility, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            user.subject(),
            emptyToNull(user.email()),
            user.displayName(),
            handle,
            emptyToNull(user.avatarUrl()),
            null,
            null,
            "",
            ProfileVisibility.PUBLIC.name(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return findProfileById(id).orElseThrow();
    }

    public Optional<ProfileRow> findProfileById(UUID id) {
        return jdbcTemplate.query(
            "select * from profiles where id = ?",
            PROFILE_ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    public ProfileRow updateProfile(UUID id, String displayName, String bio, ProfileVisibility visibility, Instant now) {
        jdbcTemplate.update(
            """
            update profiles
            set display_name = ?, bio = ?, visibility = ?, updated_at = ?
            where id = ?
            """,
            displayName,
            bio,
            visibility.name(),
            Timestamp.from(now),
            id
        );
        return findProfileById(id).orElseThrow();
    }

    public ProfileRow updateProfileAvatar(UUID id,
                                          String avatarUrl,
                                          String avatarStorageKey,
                                          String avatarContentType,
                                          Instant now) {
        jdbcTemplate.update(
            """
            update profiles
            set avatar_url = ?, avatar_storage_key = ?, avatar_content_type = ?, updated_at = ?
            where id = ?
            """,
            emptyToNull(avatarUrl),
            emptyToNull(avatarStorageKey),
            emptyToNull(avatarContentType),
            Timestamp.from(now),
            id
        );
        return findProfileById(id).orElseThrow();
    }

    public ProfileRow clearProfileAvatar(UUID id, Instant now) {
        jdbcTemplate.update(
            """
            update profiles
            set avatar_url = null, avatar_storage_key = null, avatar_content_type = null, updated_at = ?
            where id = ?
            """,
            Timestamp.from(now),
            id
        );
        return findProfileById(id).orElseThrow();
    }

    public RelationshipSnapshot relationship(UUID viewerId, UUID targetId) {
        return jdbcTemplate.queryForObject(
            """
            select
                exists(select 1 from profile_follows where follower_profile_id = ? and followed_profile_id = ?) as following,
                exists(select 1 from profile_follows where follower_profile_id = ? and followed_profile_id = ?) as follows_viewer,
                exists(select 1 from profile_access_grants where owner_profile_id = ? and viewer_profile_id = ?) as access_granted,
                (select count(*) from profile_follows where followed_profile_id = ?) as follower_count,
                (select count(*) from profile_follows where follower_profile_id = ?) as following_count
            """,
            (rs, rowNum) -> new RelationshipSnapshot(
                rs.getBoolean("following"),
                rs.getBoolean("follows_viewer"),
                rs.getBoolean("access_granted"),
                rs.getInt("follower_count"),
                rs.getInt("following_count")
            ),
            viewerId,
            targetId,
            targetId,
            viewerId,
            targetId,
            viewerId,
            targetId,
            targetId
        );
    }

    public List<ProfileSearchRow> searchProfiles(UUID viewerId, String query, boolean mutualOnly, int limit) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String sql = """
            select
                p.id,
                p.subject,
                p.email,
                p.display_name,
                p.handle,
                p.avatar_url,
                p.avatar_storage_key,
                p.avatar_content_type,
                p.bio,
                p.visibility,
                p.created_at,
                p.updated_at,
                exists(select 1 from profile_follows where follower_profile_id = ? and followed_profile_id = p.id) as following,
                exists(select 1 from profile_follows where follower_profile_id = p.id and followed_profile_id = ?) as follows_viewer,
                exists(select 1 from profile_access_grants where owner_profile_id = p.id and viewer_profile_id = ?) as access_granted
            from profiles p
            where p.id <> ?
              and (? = '' or p.handle like ? or lower(p.display_name) like ?)
              and (
                    ? = false
                    or (
                        exists(select 1 from profile_follows where follower_profile_id = ? and followed_profile_id = p.id)
                        and exists(select 1 from profile_follows where follower_profile_id = p.id and followed_profile_id = ?)
                    )
              )
            order by lower(p.display_name), p.handle
            limit ?
            """;

        String like = "%" + normalizedQuery + "%";
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new ProfileSearchRow(
                PROFILE_ROW_MAPPER.mapRow(rs, rowNum),
                rs.getBoolean("following"),
                rs.getBoolean("follows_viewer"),
                rs.getBoolean("access_granted")
            ),
            viewerId,
            viewerId,
            viewerId,
            viewerId,
            normalizedQuery,
            like,
            like,
            mutualOnly,
            viewerId,
            viewerId,
            limit
        );
    }

    public List<ProfileRow> findProfilesByHandles(Collection<String> handles) {
        if (handles.isEmpty()) {
            return List.of();
        }

        List<String> normalized = handles.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
        if (normalized.isEmpty()) {
            return List.of();
        }

        String placeholders = normalized.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        return jdbcTemplate.query(
            "select * from profiles where handle in (" + placeholders + ") order by lower(display_name), handle",
            PROFILE_ROW_MAPPER,
            normalized.toArray()
        );
    }

    public List<ProfileRow> findAccessGrantedProfiles(UUID ownerId) {
        return jdbcTemplate.query(
            """
            select p.*
            from profiles p
            join profile_access_grants g on g.viewer_profile_id = p.id
            where g.owner_profile_id = ?
            order by lower(p.display_name), p.handle
            """,
            PROFILE_ROW_MAPPER,
            ownerId
        );
    }

    public void follow(UUID followerId, UUID followedId, Instant now) {
        jdbcTemplate.update(
            """
            insert into profile_follows (follower_profile_id, followed_profile_id, created_at)
            values (?, ?, ?)
            on conflict do nothing
            """,
            followerId,
            followedId,
            Timestamp.from(now)
        );
    }

    public void unfollow(UUID followerId, UUID followedId) {
        jdbcTemplate.update(
            "delete from profile_follows where follower_profile_id = ? and followed_profile_id = ?",
            followerId,
            followedId
        );
    }

    public void grantAccess(UUID ownerId, Collection<UUID> viewerIds, Instant now) {
        for (UUID viewerId : viewerIds) {
            jdbcTemplate.update(
                """
                insert into profile_access_grants (owner_profile_id, viewer_profile_id, granted_at)
                values (?, ?, ?)
                on conflict do nothing
                """,
                ownerId,
                viewerId,
                Timestamp.from(now)
            );
        }
    }

    public PostRow createPost(UUID postId, UUID authorProfileId, String body, Instant now) {
        jdbcTemplate.update(
            """
            insert into posts (id, author_profile_id, body, created_at, updated_at)
            values (?, ?, ?, ?, ?)
            """,
            postId,
            authorProfileId,
            body,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return findPostById(postId).orElseThrow();
    }

    public MediaAssetRow createMedia(UUID mediaId,
                              UUID ownerProfileId,
                              String storageKey,
                              String originalFilename,
                              String mimeType,
                              MediaKind kind,
                              long fileSize,
                              Instant now) {
        jdbcTemplate.update(
            """
            insert into media_assets (id, owner_profile_id, storage_key, original_filename, mime_type, media_kind, file_size, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            mediaId,
            ownerProfileId,
            storageKey,
            originalFilename,
            mimeType,
            kind.name(),
            fileSize,
            Timestamp.from(now)
        );
        return findMediaById(mediaId).orElseThrow();
    }

    public List<MediaAssetRow> findMediaByIdsAndOwner(Collection<UUID> mediaIds, UUID ownerProfileId) {
        if (mediaIds.isEmpty()) {
            return List.of();
        }

        String placeholders = mediaIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        params.add(ownerProfileId);
        params.addAll(mediaIds);
        return jdbcTemplate.query(
            """
            select
                m.id as media_id,
                m.owner_profile_id,
                m.storage_key,
                m.original_filename,
                m.mime_type,
                m.media_kind,
                m.file_size,
                m.created_at as media_created_at
            from media_assets m
            where m.owner_profile_id = ?
              and m.id in (%s)
            order by m.created_at asc
            """.formatted(placeholders),
            MEDIA_ROW_MAPPER,
            params.toArray()
        );
    }

    public Optional<MediaAssetRow> findMediaById(UUID mediaId) {
        return jdbcTemplate.query(
            """
            select
                m.id as media_id,
                m.owner_profile_id,
                m.storage_key,
                m.original_filename,
                m.mime_type,
                m.media_kind,
                m.file_size,
                m.created_at as media_created_at
            from media_assets m
            where m.id = ?
            """,
            MEDIA_ROW_MAPPER,
            mediaId
        ).stream().findFirst();
    }

    public void attachMedia(UUID postId, List<UUID> mediaIds) {
        for (int index = 0; index < mediaIds.size(); index++) {
            jdbcTemplate.update(
                """
                insert into post_media_assets (post_id, media_asset_id, sort_order)
                values (?, ?, ?)
                """,
                postId,
                mediaIds.get(index),
                index
            );
        }
    }

    public Optional<PostRow> findPostById(UUID postId) {
        return jdbcTemplate.query(
            """
            select
                p.id as post_id,
                p.author_profile_id,
                p.body,
                p.created_at,
                a.handle as author_handle,
                a.display_name as author_display_name,
                a.avatar_url as author_avatar_url
            from posts p
            join profiles a on a.id = p.author_profile_id
            where p.id = ?
            """,
            POST_ROW_MAPPER,
            postId
        ).stream().findFirst();
    }

    public List<PostRow> findRecentPostsForAuthor(UUID authorId, int limit) {
        return jdbcTemplate.query(
            """
            select
                p.id as post_id,
                p.author_profile_id,
                p.body,
                p.created_at,
                a.handle as author_handle,
                a.display_name as author_display_name,
                a.avatar_url as author_avatar_url
            from posts p
            join profiles a on a.id = p.author_profile_id
            where p.author_profile_id = ?
            order by p.created_at desc
            limit ?
            """,
            POST_ROW_MAPPER,
            authorId,
            limit
        );
    }

    public List<PostRow> findFeedPosts(UUID viewerId, int limit) {
        return jdbcTemplate.query(
            """
            select
                p.id as post_id,
                p.author_profile_id,
                p.body,
                p.created_at,
                a.handle as author_handle,
                a.display_name as author_display_name,
                a.avatar_url as author_avatar_url
            from posts p
            join profiles a on a.id = p.author_profile_id
            where a.id = ?
               or (
                    a.visibility = 'PUBLIC'
                    and exists(select 1 from profile_follows f where f.follower_profile_id = ? and f.followed_profile_id = a.id)
               )
               or exists(select 1 from profile_access_grants g where g.owner_profile_id = a.id and g.viewer_profile_id = ?)
               or (
                    exists(select 1 from profile_follows f where f.follower_profile_id = ? and f.followed_profile_id = a.id)
                    and exists(select 1 from profile_follows f where f.follower_profile_id = a.id and f.followed_profile_id = ?)
               )
            order by p.created_at desc
            limit ?
            """,
            POST_ROW_MAPPER,
            viewerId,
            viewerId,
            viewerId,
            viewerId,
            viewerId,
            limit
        );
    }

    public Map<UUID, List<MediaAssetRow>> loadMediaForPosts(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = postIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select
                pma.post_id,
                m.id as media_id,
                m.owner_profile_id,
                m.storage_key,
                m.original_filename,
                m.mime_type,
                m.media_kind,
                m.file_size,
                m.created_at as media_created_at
            from post_media_assets pma
            join media_assets m on m.id = pma.media_asset_id
            where pma.post_id in (%s)
            order by pma.post_id, pma.sort_order asc
            """.formatted(placeholders),
            postIds.toArray()
        );

        Map<UUID, List<MediaAssetRow>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID postId = (UUID) row.get("post_id");
            grouped.computeIfAbsent(postId, ignored -> new ArrayList<>()).add(new MediaAssetRow(
                (UUID) row.get("media_id"),
                (UUID) row.get("owner_profile_id"),
                (String) row.get("storage_key"),
                (String) row.get("original_filename"),
                (String) row.get("mime_type"),
                MediaKind.valueOf((String) row.get("media_kind")),
                ((Number) row.get("file_size")).longValue(),
                timestamp(row.get("media_created_at"))
            ));
        }
        return grouped;
    }

    public Optional<MediaAccessRow> findMediaAccessById(UUID mediaId) {
        return jdbcTemplate.query(
            """
            select
                m.id as media_id,
                m.owner_profile_id,
                m.storage_key,
                m.original_filename,
                m.mime_type,
                m.media_kind,
                m.file_size,
                m.created_at as media_created_at,
                pma.post_id,
                p.author_profile_id
            from media_assets m
            left join post_media_assets pma on pma.media_asset_id = m.id
            left join posts p on p.id = pma.post_id
            where m.id = ?
            """,
            (rs, rowNum) -> new MediaAccessRow(
                MEDIA_ROW_MAPPER.mapRow(rs, rowNum),
                rs.getObject("post_id", UUID.class),
                rs.getObject("author_profile_id", UUID.class)
            ),
            mediaId
        ).stream().findFirst();
    }

    public Map<UUID, Map<String, Integer>> loadReactionCounts(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = postIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select post_id, reaction_type, count(*) as total
            from post_reactions
            where post_id in (%s)
            group by post_id, reaction_type
            """.formatted(placeholders),
            postIds.toArray()
        );

        Map<UUID, Map<String, Integer>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID postId = (UUID) row.get("post_id");
            String reactionType = (String) row.get("reaction_type");
            Number total = (Number) row.get("total");
            grouped.computeIfAbsent(postId, ignored -> new LinkedHashMap<>()).put(reactionType, total.intValue());
        }
        return grouped;
    }

    public Map<UUID, Set<String>> loadViewerReactions(UUID viewerId, Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = postIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        List<Object> params = new ArrayList<>();
        params.add(viewerId);
        params.addAll(postIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            select post_id, reaction_type
            from post_reactions
            where reactor_profile_id = ?
              and post_id in (%s)
            """.formatted(placeholders),
            params.toArray()
        );

        Map<UUID, Set<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID postId = (UUID) row.get("post_id");
            String reactionType = (String) row.get("reaction_type");
            grouped.computeIfAbsent(postId, ignored -> new LinkedHashSet<>()).add(reactionType);
        }
        return grouped;
    }

    public void addReaction(UUID postId, UUID reactorProfileId, String reactionType, Instant now) {
        jdbcTemplate.update(
            """
            insert into post_reactions (post_id, reactor_profile_id, reaction_type, created_at)
            values (?, ?, ?, ?)
            on conflict do nothing
            """,
            postId,
            reactorProfileId,
            reactionType,
            Timestamp.from(now)
        );
    }

    public void removeReaction(UUID postId, UUID reactorProfileId, String reactionType) {
        jdbcTemplate.update(
            """
            delete from post_reactions
            where post_id = ? and reactor_profile_id = ? and reaction_type = ?
            """,
            postId,
            reactorProfileId,
            reactionType
        );
    }

    private static Instant timestamp(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Instant timestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass().getName());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ProfileRow(
        UUID id,
        String subject,
        String email,
        String displayName,
        String handle,
        String avatarUrl,
        String avatarStorageKey,
        String avatarContentType,
        String bio,
        ProfileVisibility visibility,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record RelationshipSnapshot(
        boolean following,
        boolean followsViewer,
        boolean accessGranted,
        int followerCount,
        int followingCount
    ) {
    }

    public record ProfileSearchRow(ProfileRow profile, boolean following, boolean followsViewer, boolean accessGranted) {
    }

    public record PostRow(
        UUID id,
        UUID authorProfileId,
        String body,
        Instant createdAt,
        String authorHandle,
        String authorDisplayName,
        String authorAvatarUrl
    ) {
    }

    public record MediaAssetRow(
        UUID id,
        UUID ownerProfileId,
        String storageKey,
        String originalFilename,
        String mimeType,
        MediaKind kind,
        long fileSize,
        Instant createdAt
    ) {
    }

    public record MediaAccessRow(MediaAssetRow media, UUID postId, UUID authorProfileId) {
    }
}

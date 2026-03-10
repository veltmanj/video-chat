package nl.nextend.videobackoffice.social;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class SocialJdbcRepository {

    private static final RowMapper<ProfileRow> PROFILE_ROW_MAPPER = (rs, rowNum) -> new ProfileRow(
        rs.getObject("id", UUID.class),
        rs.getString("subject"),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getString("handle"),
        rs.getString("avatar_url"),
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

    private final JdbcTemplate jdbcTemplate;

    SocialJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<ProfileRow> findProfileBySubject(String subject) {
        return jdbcTemplate.query(
            "select * from profiles where subject = ?",
            PROFILE_ROW_MAPPER,
            subject
        ).stream().findFirst();
    }

    Optional<ProfileRow> findProfileByHandle(String handle) {
        return jdbcTemplate.query(
            "select * from profiles where handle = ?",
            PROFILE_ROW_MAPPER,
            handle
        ).stream().findFirst();
    }

    boolean existsHandle(String handle) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from profiles where handle = ?",
            Integer.class,
            handle
        );
        return count != null && count > 0;
    }

    ProfileRow insertProfile(UUID id, AuthUser user, String handle, Instant now) {
        jdbcTemplate.update(
            """
            insert into profiles (id, subject, email, display_name, handle, avatar_url, bio, visibility, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            user.subject(),
            emptyToNull(user.email()),
            user.displayName(),
            handle,
            emptyToNull(user.avatarUrl()),
            "",
            ProfileVisibility.PUBLIC.name(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return findProfileById(id).orElseThrow();
    }

    Optional<ProfileRow> findProfileById(UUID id) {
        return jdbcTemplate.query(
            "select * from profiles where id = ?",
            PROFILE_ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    ProfileRow updateProfile(UUID id, String displayName, String bio, ProfileVisibility visibility, Instant now) {
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

    RelationshipSnapshot relationship(UUID viewerId, UUID targetId) {
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

    List<ProfileSearchRow> searchProfiles(UUID viewerId, String query, boolean mutualOnly, int limit) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String sql = """
            select
                p.id,
                p.subject,
                p.email,
                p.display_name,
                p.handle,
                p.avatar_url,
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

    List<ProfileRow> findProfilesByHandles(Collection<String> handles) {
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

    List<ProfileRow> findAccessGrantedProfiles(UUID ownerId) {
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

    void follow(UUID followerId, UUID followedId, Instant now) {
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

    void unfollow(UUID followerId, UUID followedId) {
        jdbcTemplate.update(
            "delete from profile_follows where follower_profile_id = ? and followed_profile_id = ?",
            followerId,
            followedId
        );
    }

    void grantAccess(UUID ownerId, Collection<UUID> viewerIds, Instant now) {
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

    PostRow createPost(UUID postId, UUID authorProfileId, String body, Instant now) {
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

    Optional<PostRow> findPostById(UUID postId) {
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

    List<PostRow> findRecentPostsForAuthor(UUID authorId, int limit) {
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

    List<PostRow> findFeedPosts(UUID viewerId, int limit) {
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

    Map<UUID, Map<String, Integer>> loadReactionCounts(Collection<UUID> postIds) {
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

    Map<UUID, Set<String>> loadViewerReactions(UUID viewerId, Collection<UUID> postIds) {
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

    void addReaction(UUID postId, UUID reactorProfileId, String reactionType, Instant now) {
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

    void removeReaction(UUID postId, UUID reactorProfileId, String reactionType) {
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record ProfileRow(
        UUID id,
        String subject,
        String email,
        String displayName,
        String handle,
        String avatarUrl,
        String bio,
        ProfileVisibility visibility,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    record RelationshipSnapshot(
        boolean following,
        boolean followsViewer,
        boolean accessGranted,
        int followerCount,
        int followingCount
    ) {
    }

    record ProfileSearchRow(ProfileRow profile, boolean following, boolean followsViewer, boolean accessGranted) {
    }

    record PostRow(
        UUID id,
        UUID authorProfileId,
        String body,
        Instant createdAt,
        String authorHandle,
        String authorDisplayName,
        String authorAvatarUrl
    ) {
    }
}

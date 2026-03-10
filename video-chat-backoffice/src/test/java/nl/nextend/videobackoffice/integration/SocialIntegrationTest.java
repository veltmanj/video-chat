package nl.nextend.videobackoffice.integration;

import java.util.List;

import nl.nextend.videobackoffice.social.ProfileVisibility;
import nl.nextend.videobackoffice.social.SocialApi.FeedResponse;
import nl.nextend.videobackoffice.social.SocialApi.GrantAccessResult;
import nl.nextend.videobackoffice.social.SocialApi.PostResponse;
import nl.nextend.videobackoffice.social.SocialApi.ProfileResponse;
import nl.nextend.videobackoffice.social.SocialApi.ProfileSummary;
import nl.nextend.videobackoffice.social.SocialApi.ViewerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocialIntegrationTest {

    private static final TestUser ALICE = new TestUser("social-alice-sub", "alice@example.com", "Alice Example");
    private static final TestUser BOB = new TestUser("social-bob-sub", "bob@example.com", "Bob Example");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
            .apply(springSecurity())
            .configureClient()
            .build();
        initializeSchema();
        clearSocialTables();
    }

    @Test
    void shouldGrantInvitationAccessToPrivateProfilesAndPersistReactions() {
        ViewerResponse aliceViewer = viewer(ALICE);
        ViewerResponse bobViewer = viewer(BOB);

        assertThat(aliceViewer.me().handle()).isEqualTo("alice");
        assertThat(bobViewer.me().handle()).isEqualTo("bob");

        ProfileResponse aliceProfile = updateProfile(ALICE, "Alice Example", "Private updates only", ProfileVisibility.PRIVATE);
        assertThat(aliceProfile.visibility()).isEqualTo(ProfileVisibility.PRIVATE);

        PostResponse alicePost = createPost(ALICE, "Private update");
        assertThat(feed(BOB).posts()).isEmpty();

        profileRequest(BOB, aliceProfile.handle())
            .exchange()
            .expectStatus().isForbidden();

        GrantAccessResult accessResult = auth(ALICE)
            .post()
            .uri("/social/v1/profiles/{handle}/access-grants", aliceProfile.handle())
            .bodyValue(new ViewerHandlesRequest(List.of("bob", "missing-user")))
            .exchange()
            .expectStatus().isOk()
            .expectBody(GrantAccessResult.class)
            .returnResult()
            .getResponseBody();

        assertThat(accessResult).isNotNull();
        assertThat(accessResult.grantedHandles()).containsExactly("bob");
        assertThat(accessResult.missingHandles()).containsExactly("missing-user");

        ProfileResponse bobViewOfAlice = profileRequest(BOB, aliceProfile.handle())
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProfileResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(bobViewOfAlice).isNotNull();
        assertThat(bobViewOfAlice.canView()).isTrue();
        assertThat(bobViewOfAlice.accessGranted()).isTrue();
        assertThat(bobViewOfAlice.recentPosts()).extracting(PostResponse::id).containsExactly(alicePost.id());

        FeedResponse bobFeed = feed(BOB);
        assertThat(bobFeed.posts()).extracting(PostResponse::id).containsExactly(alicePost.id());

        PostResponse reacted = auth(BOB)
            .post()
            .uri("/social/v1/posts/{postId}/reactions", alicePost.id())
            .bodyValue(new ReactionRequest("fire"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(PostResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(reacted).isNotNull();
        assertThat(reacted.viewerReactions()).containsExactly("fire");
        assertThat(reacted.reactionCounts()).containsEntry("fire", 1);

        PostResponse unreacted = auth(BOB)
            .delete()
            .uri("/social/v1/posts/{postId}/reactions/{reactionType}", alicePost.id(), "fire")
            .exchange()
            .expectStatus().isOk()
            .expectBody(PostResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(unreacted).isNotNull();
        assertThat(unreacted.viewerReactions()).isEmpty();
        assertThat(unreacted.reactionCounts()).doesNotContainKey("fire");
    }

    @Test
    void shouldRequireMutualFollowBeforeViewingPrivateProfilesWithoutInvitation() {
        ProfileResponse aliceProfile = updateProfile(ALICE, "Alice Example", "Friends only", ProfileVisibility.PRIVATE);
        viewer(BOB);

        ProfileSummary bobFollowSummary = auth(BOB)
            .post()
            .uri("/social/v1/profiles/{handle}/follow", aliceProfile.handle())
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProfileSummary.class)
            .returnResult()
            .getResponseBody();

        assertThat(bobFollowSummary).isNotNull();
        assertThat(bobFollowSummary.following()).isTrue();
        assertThat(bobFollowSummary.mutualConnection()).isFalse();
        assertThat(bobFollowSummary.canView()).isFalse();

        profileRequest(BOB, aliceProfile.handle())
            .exchange()
            .expectStatus().isForbidden();

        ProfileSummary aliceFollowSummary = auth(ALICE)
            .post()
            .uri("/social/v1/profiles/{handle}/follow", "bob")
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProfileSummary.class)
            .returnResult()
            .getResponseBody();

        assertThat(aliceFollowSummary).isNotNull();
        assertThat(aliceFollowSummary.following()).isTrue();
        assertThat(aliceFollowSummary.mutualConnection()).isTrue();
        assertThat(aliceFollowSummary.canView()).isTrue();

        ProfileResponse bobViewOfAlice = profileRequest(BOB, aliceProfile.handle())
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProfileResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(bobViewOfAlice).isNotNull();
        assertThat(bobViewOfAlice.canView()).isTrue();
        assertThat(bobViewOfAlice.mutualConnection()).isTrue();

        List<ProfileSummary> mutualResults = auth(BOB)
            .get()
            .uri(uriBuilder -> uriBuilder.path("/social/v1/profiles/search")
                .queryParam("q", "alice")
                .queryParam("scope", "mutual")
                .build())
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(ProfileSummary.class)
            .returnResult()
            .getResponseBody();

        assertThat(mutualResults).isNotNull();
        assertThat(mutualResults).hasSize(1);
        assertThat(mutualResults.get(0).handle()).isEqualTo("alice");
        assertThat(mutualResults.get(0).mutualConnection()).isTrue();
        assertThat(mutualResults.get(0).canView()).isTrue();
    }

    private ViewerResponse viewer(TestUser user) {
        return auth(user)
            .get()
            .uri("/social/v1/me")
            .exchange()
            .expectStatus().isOk()
            .expectBody(ViewerResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private ProfileResponse updateProfile(TestUser user, String displayName, String bio, ProfileVisibility visibility) {
        return auth(user)
            .put()
            .uri("/social/v1/me")
            .bodyValue(new UpdateProfileRequest(displayName, bio, visibility))
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProfileResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private PostResponse createPost(TestUser user, String body) {
        return auth(user)
            .post()
            .uri("/social/v1/posts")
            .bodyValue(new CreatePostRequest(body))
            .exchange()
            .expectStatus().isOk()
            .expectBody(PostResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private FeedResponse feed(TestUser user) {
        return auth(user)
            .get()
            .uri("/social/v1/feed")
            .exchange()
            .expectStatus().isOk()
            .expectBody(FeedResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private WebTestClient.RequestHeadersSpec<?> profileRequest(TestUser user, String handle) {
        return auth(user)
            .get()
            .uri("/social/v1/profiles/{handle}", handle);
    }

    private WebTestClient auth(TestUser user) {
        JwtMutator jwt = mockJwt().jwt(builder -> builder
            .subject(user.subject())
            .claim("email", user.email())
            .claim("name", user.displayName()));
        return webTestClient.mutateWith(jwt);
    }

    private void clearSocialTables() {
        jdbcTemplate.execute("delete from post_reactions");
        jdbcTemplate.execute("delete from posts");
        jdbcTemplate.execute("delete from profile_access_grants");
        jdbcTemplate.execute("delete from profile_follows");
        jdbcTemplate.execute("delete from profiles");
    }

    private void initializeSchema() {
        jdbcTemplate.execute("""
            create table if not exists profiles (
                id uuid primary key,
                subject varchar(255) not null unique,
                email varchar(320),
                display_name varchar(80) not null,
                handle varchar(32) not null unique,
                avatar_url text,
                bio varchar(400) not null default '',
                visibility varchar(16) not null,
                created_at timestamp not null,
                updated_at timestamp not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists profile_follows (
                follower_profile_id uuid not null references profiles(id) on delete cascade,
                followed_profile_id uuid not null references profiles(id) on delete cascade,
                created_at timestamp not null,
                primary key (follower_profile_id, followed_profile_id),
                check (follower_profile_id <> followed_profile_id)
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists profile_access_grants (
                owner_profile_id uuid not null references profiles(id) on delete cascade,
                viewer_profile_id uuid not null references profiles(id) on delete cascade,
                granted_at timestamp not null,
                primary key (owner_profile_id, viewer_profile_id),
                check (owner_profile_id <> viewer_profile_id)
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists posts (
                id uuid primary key,
                author_profile_id uuid not null references profiles(id) on delete cascade,
                body varchar(2000) not null,
                created_at timestamp not null,
                updated_at timestamp not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists post_reactions (
                post_id uuid not null references posts(id) on delete cascade,
                reactor_profile_id uuid not null references profiles(id) on delete cascade,
                reaction_type varchar(32) not null,
                created_at timestamp not null,
                primary key (post_id, reactor_profile_id, reaction_type)
            )
            """);
        jdbcTemplate.execute("create index if not exists idx_profiles_handle on profiles (handle)");
        jdbcTemplate.execute("create index if not exists idx_posts_created on posts (created_at desc)");
    }

    private record TestUser(String subject, String email, String displayName) {
    }

    private record UpdateProfileRequest(String displayName, String bio, ProfileVisibility visibility) {
    }

    private record CreatePostRequest(String body) {
    }

    private record ReactionRequest(String reactionType) {
    }

    private record ViewerHandlesRequest(List<String> viewerHandles) {
    }
}

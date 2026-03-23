package nl.nextend.videobackoffice.social.profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.ProfileRow;
import nl.nextend.videobackoffice.social.repository.SocialJdbcRepository.RelationshipSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocialProfileSupportTest {

    @Test
    void ensureProfileCreatesANewProfileWhenTheSubjectIsUnknown() {
        SocialJdbcRepository repository = mock(SocialJdbcRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-23T10:15:30Z"), ZoneOffset.UTC);
        SocialProfileSupport support = new SocialProfileSupport(repository, clock);
        AuthUser user = new AuthUser("subject-1", "alice@example.com", "Alice Example", "");
        ProfileRow created = profileRow("alice", ProfileVisibility.PUBLIC);

        when(repository.findProfileBySubject("subject-1")).thenReturn(Optional.empty());
        when(repository.insertProfile(any(), eq(user), eq("alice"), eq(Instant.now(clock)))).thenReturn(created);

        assertThat(support.ensureProfile(user)).isEqualTo(created);
    }

    @Test
    void canViewAlwaysReturnsTrueForPublicProfiles() {
        SocialProfileSupport support = new SocialProfileSupport(mock(SocialJdbcRepository.class), Clock.systemUTC());

        boolean visible = support.canView(profileRow("alice", ProfileVisibility.PUBLIC), false, false, false);

        assertThat(visible).isTrue();
    }

    @Test
    void canViewAllowsPrivateProfilesWhenAccessWasGranted() {
        SocialProfileSupport support = new SocialProfileSupport(mock(SocialJdbcRepository.class), Clock.systemUTC());

        boolean visible = support.canView(profileRow("alice", ProfileVisibility.PRIVATE), false, false, true);

        assertThat(visible).isTrue();
    }

    @Test
    void normalizeHandleRejectsBlankValues() {
        SocialProfileSupport support = new SocialProfileSupport(mock(SocialJdbcRepository.class), Clock.systemUTC());

        assertThatThrownBy(() -> support.normalizeHandle("   "))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void toSummaryReflectsMutualRelationshipFlags() {
        SocialProfileSupport support = new SocialProfileSupport(mock(SocialJdbcRepository.class), Clock.systemUTC());
        ProfileRow target = profileRow("alice", ProfileVisibility.PRIVATE);
        RelationshipSnapshot relationship = new RelationshipSnapshot(true, true, false, 3, 5);

        var summary = support.toSummary(target, relationship);

        assertThat(summary.canView()).isTrue();
        assertThat(summary.following()).isTrue();
        assertThat(summary.followsViewer()).isTrue();
        assertThat(summary.mutualConnection()).isTrue();
    }

    private static ProfileRow profileRow(String handle, ProfileVisibility visibility) {
        return new ProfileRow(
            UUID.randomUUID(),
            handle + "-subject",
            handle + "@example.com",
            handle,
            handle,
            "",
            "",
            visibility,
            Instant.parse("2026-03-23T10:15:30Z"),
            Instant.parse("2026-03-23T10:15:30Z")
        );
    }
}

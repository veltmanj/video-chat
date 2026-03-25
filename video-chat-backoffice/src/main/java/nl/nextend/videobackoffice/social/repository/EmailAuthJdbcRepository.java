package nl.nextend.videobackoffice.social.repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import nl.nextend.videobackoffice.social.auth.EmailAuthChallengePurpose;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EmailAuthJdbcRepository {

    private static final RowMapper<EmailAccountRow> EMAIL_ACCOUNT_ROW_MAPPER = (rs, rowNum) -> new EmailAccountRow(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("display_name"),
        timestamp(rs, "verified_at"),
        timestamp(rs, "created_at"),
        timestamp(rs, "updated_at")
    );

    private static final RowMapper<EmailAuthChallengeRow> EMAIL_AUTH_CHALLENGE_ROW_MAPPER = (rs, rowNum) -> new EmailAuthChallengeRow(
        rs.getObject("id", UUID.class),
        rs.getObject("email_account_id", UUID.class),
        EmailAuthChallengePurpose.valueOf(rs.getString("purpose")),
        rs.getString("token_hash"),
        timestamp(rs, "expires_at"),
        timestamp(rs, "created_at"),
        timestamp(rs, "consumed_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public EmailAuthJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EmailAccountRow> findEmailAccountByEmail(String email) {
        return jdbcTemplate.query(
            "select * from email_accounts where email = ?",
            EMAIL_ACCOUNT_ROW_MAPPER,
            email
        ).stream().findFirst();
    }

    public Optional<EmailAccountRow> findEmailAccountById(UUID id) {
        return jdbcTemplate.query(
            "select * from email_accounts where id = ?",
            EMAIL_ACCOUNT_ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    public EmailAccountRow insertEmailAccount(UUID id, String email, String displayName, Instant now) {
        jdbcTemplate.update(
            """
            insert into email_accounts (id, email, display_name, verified_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """,
            id,
            email,
            displayName,
            null,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return findEmailAccountById(id).orElseThrow();
    }

    public EmailAccountRow updateEmailAccountDisplayName(UUID id, String displayName, Instant now) {
        jdbcTemplate.update(
            """
            update email_accounts
            set display_name = ?, updated_at = ?
            where id = ?
            """,
            displayName,
            Timestamp.from(now),
            id
        );
        return findEmailAccountById(id).orElseThrow();
    }

    public EmailAccountRow markEmailVerified(UUID id, Instant now) {
        jdbcTemplate.update(
            """
            update email_accounts
            set verified_at = coalesce(verified_at, ?), updated_at = ?
            where id = ?
            """,
            Timestamp.from(now),
            Timestamp.from(now),
            id
        );
        return findEmailAccountById(id).orElseThrow();
    }

    public EmailAuthChallengeRow createChallenge(UUID id,
                                                 UUID emailAccountId,
                                                 EmailAuthChallengePurpose purpose,
                                                 String tokenHash,
                                                 Instant now,
                                                 Instant expiresAt) {
        jdbcTemplate.update(
            """
            insert into email_auth_challenges (id, email_account_id, purpose, token_hash, expires_at, created_at, consumed_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            emailAccountId,
            purpose.name(),
            tokenHash,
            Timestamp.from(expiresAt),
            Timestamp.from(now),
            null
        );
        return findChallengeById(id).orElseThrow();
    }

    public Optional<EmailAuthChallengeRow> findChallengeByTokenHash(String tokenHash) {
        return jdbcTemplate.query(
            "select * from email_auth_challenges where token_hash = ?",
            EMAIL_AUTH_CHALLENGE_ROW_MAPPER,
            tokenHash
        ).stream().findFirst();
    }

    public void consumeChallenge(UUID id, Instant now) {
        jdbcTemplate.update(
            """
            update email_auth_challenges
            set consumed_at = coalesce(consumed_at, ?)
            where id = ?
            """,
            Timestamp.from(now),
            id
        );
    }

    private Optional<EmailAuthChallengeRow> findChallengeById(UUID id) {
        return jdbcTemplate.query(
            "select * from email_auth_challenges where id = ?",
            EMAIL_AUTH_CHALLENGE_ROW_MAPPER,
            id
        ).stream().findFirst();
    }

    private static Instant timestamp(ResultSet resultSet, String columnName) throws java.sql.SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record EmailAccountRow(
        UUID id,
        String email,
        String displayName,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record EmailAuthChallengeRow(
        UUID id,
        UUID emailAccountId,
        EmailAuthChallengePurpose purpose,
        String tokenHash,
        Instant expiresAt,
        Instant createdAt,
        Instant consumedAt
    ) {
    }
}

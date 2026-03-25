package nl.nextend.videobackoffice.social.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailAuthStartResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailLoginRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailRegistrationRequest;
import nl.nextend.videobackoffice.social.repository.EmailAuthJdbcRepository;
import nl.nextend.videobackoffice.social.repository.EmailAuthJdbcRepository.EmailAccountRow;
import nl.nextend.videobackoffice.social.repository.EmailAuthJdbcRepository.EmailAuthChallengeRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class EmailAuthService {

    private static final DateTimeFormatter HUMAN_EXPIRY_FORMAT = DateTimeFormatter
        .ofPattern("EEEE d MMMM yyyy 'at' HH:mm 'UTC'")
        .withZone(ZoneId.of("UTC"));

    private final EmailAuthJdbcRepository repository;
    private final EmailAuthJwtService jwtService;
    private final BackofficeSocialProperties properties;
    private final EmailAuthMailer mailer;
    private final Clock clock;
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    @Autowired
    public EmailAuthService(EmailAuthJdbcRepository repository,
                            EmailAuthJwtService jwtService,
                            BackofficeSocialProperties properties,
                            ObjectProvider<EmailAuthMailer> mailerProvider) {
        this(repository, jwtService, properties, mailerProvider.getIfAvailable(), Clock.systemUTC());
    }

    EmailAuthService(EmailAuthJdbcRepository repository,
                     EmailAuthJwtService jwtService,
                     BackofficeSocialProperties properties,
                     EmailAuthMailer mailer,
                     Clock clock) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.properties = properties;
        this.mailer = mailer;
        this.clock = clock;
    }

    public EmailAuthStartResponse register(EmailRegistrationRequest request) {
        requireEmailAuthAvailable();

        Instant now = Instant.now(clock);
        String email = normalizeEmail(request.email());
        String displayName = normalizeDisplayName(request.displayName(), email);
        Optional<EmailAccountRow> existing = repository.findEmailAccountByEmail(email);

        if (existing.isEmpty()) {
            EmailAccountRow created = repository.insertEmailAccount(UUID.randomUUID(), email, displayName, now);
            sendChallenge(created, EmailAuthChallengePurpose.REGISTRATION, now);
            return new EmailAuthStartResponse("We sent a verification link to your email address.");
        }

        EmailAccountRow account = existing.get();
        if (account.verifiedAt() == null) {
            account = repository.updateEmailAccountDisplayName(account.id(), displayName, now);
            sendChallenge(account, EmailAuthChallengePurpose.REGISTRATION, now);
            return new EmailAuthStartResponse("We sent a fresh verification link to your email address.");
        }

        sendChallenge(account, EmailAuthChallengePurpose.LOGIN, now);
        return new EmailAuthStartResponse("That email is already registered. We sent a secure sign-in link instead.");
    }

    public EmailAuthStartResponse login(EmailLoginRequest request) {
        requireEmailAuthAvailable();

        Instant now = Instant.now(clock);
        String email = normalizeEmail(request.email());
        Optional<EmailAccountRow> existing = repository.findEmailAccountByEmail(email);
        if (existing.isEmpty()) {
            return new EmailAuthStartResponse("If that email is registered, a secure sign-in link is on its way.");
        }

        EmailAccountRow account = existing.get();
        if (account.verifiedAt() == null) {
            sendChallenge(account, EmailAuthChallengePurpose.REGISTRATION, now);
            return new EmailAuthStartResponse("That email still needs verification. We sent a fresh verification link.");
        }

        sendChallenge(account, EmailAuthChallengePurpose.LOGIN, now);
        return new EmailAuthStartResponse("Check your email for a secure sign-in link.");
    }

    public String jwksJson() {
        return jwtService.jwksJson();
    }

    public java.net.URI completeRegistration(String token) {
        return completeChallenge(token, EmailAuthChallengePurpose.REGISTRATION, "verified");
    }

    public java.net.URI completeLogin(String token) {
        return completeChallenge(token, EmailAuthChallengePurpose.LOGIN, "signed-in");
    }

    private java.net.URI completeChallenge(String token, EmailAuthChallengePurpose expectedPurpose, String successStatus) {
        if (!properties.getEmail().isEnabled()) {
            return redirectWithStatus("disabled");
        }

        String normalizedToken = token == null ? "" : token.trim();
        if (!StringUtils.hasText(normalizedToken)) {
            return redirectWithStatus("invalid");
        }

        Instant now = Instant.now(clock);
        Optional<EmailAuthChallengeRow> match = repository.findChallengeByTokenHash(hashToken(normalizedToken));
        if (match.isEmpty()) {
            return redirectWithStatus("invalid");
        }

        EmailAuthChallengeRow challenge = match.get();
        if (challenge.purpose() != expectedPurpose) {
            return redirectWithStatus("invalid");
        }
        if (challenge.consumedAt() != null) {
            return redirectWithStatus("invalid");
        }
        if (challenge.expiresAt().isBefore(now)) {
            return redirectWithStatus("expired");
        }

        EmailAccountRow account = repository.findEmailAccountById(challenge.emailAccountId()).orElse(null);
        if (account == null) {
            return redirectWithStatus("invalid");
        }

        if (expectedPurpose == EmailAuthChallengePurpose.REGISTRATION && account.verifiedAt() == null) {
            account = repository.markEmailVerified(account.id(), now);
        }

        repository.consumeChallenge(challenge.id(), now);
        String sessionToken = jwtService.issueSessionToken(account);
        return redirectWithToken(successStatus, sessionToken);
    }

    private void sendChallenge(EmailAccountRow account, EmailAuthChallengePurpose purpose, Instant now) {
        String rawToken = randomToken();
        Instant expiresAt = now.plus(purpose == EmailAuthChallengePurpose.REGISTRATION
            ? properties.getEmail().getRegistrationLinkTtl()
            : properties.getEmail().getLoginLinkTtl());

        repository.createChallenge(
            UUID.randomUUID(),
            account.id(),
            purpose,
            hashToken(rawToken),
            now,
            expiresAt
        );

        String link = buildPublicActionLink(purpose, rawToken);
        String subject = purpose == EmailAuthChallengePurpose.REGISTRATION
            ? "Verify your PulseRoom email address"
            : "Your secure PulseRoom sign-in link";
        String actionLabel = purpose == EmailAuthChallengePurpose.REGISTRATION
            ? "Verify email address"
            : "Sign in securely";
        String intro = purpose == EmailAuthChallengePurpose.REGISTRATION
            ? "Your PulseRoom account is almost ready. Confirm this email address to activate secure sign-in."
            : "Use the secure link below to finish signing in to PulseRoom.";

        mailer.sendHtml(
            account.email(),
            account.displayName(),
            subject,
            buildHtmlEmail(account.displayName(), intro, actionLabel, link, expiresAt)
        );
    }

    private String buildPublicActionLink(EmailAuthChallengePurpose purpose, String token) {
        String actionPath = purpose == EmailAuthChallengePurpose.REGISTRATION
            ? "/social-api/social/v1/auth/email/verify"
            : "/social-api/social/v1/auth/email/authenticate";
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getEmail().getPublicBaseUrl()))
            .path(actionPath)
            .queryParam("token", token)
            .build(true)
            .toUriString();
    }

    private java.net.URI redirectWithToken(String status, String sessionToken) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getEmail().getPublicBaseUrl()))
            .path(normalizeLoginPath(properties.getEmail().getLoginPath()))
            .queryParam("email_status", status)
            .queryParam("email_token", sessionToken)
            .build(true)
            .toUri();
    }

    private java.net.URI redirectWithStatus(String status) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(properties.getEmail().getPublicBaseUrl()))
            .path(normalizeLoginPath(properties.getEmail().getLoginPath()))
            .queryParam("email_status", status)
            .build(true)
            .toUri();
    }

    private void requireEmailAuthAvailable() {
        if (!properties.getEmail().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email authentication is not configured.");
        }
        if (mailer == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is not configured.");
        }
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (!StringUtils.hasText(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email address is required.");
        }
        return normalized;
    }

    private String normalizeDisplayName(String displayName, String email) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(0, atIndex) : email;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash the email-auth token.", exception);
        }
    }

    private String buildHtmlEmail(String displayName,
                                  String intro,
                                  String actionLabel,
                                  String actionLink,
                                  Instant expiresAt) {
        String escapedName = html(displayName);
        String escapedIntro = html(intro);
        String escapedActionLabel = html(actionLabel);
        String escapedActionLink = html(actionLink);
        String humanExpiry = html(HUMAN_EXPIRY_FORMAT.format(expiresAt));

        return """
            <html>
            <body style="margin:0;padding:0;background:#f8fafc;font-family:Arial,sans-serif;color:#0f172a;">
              <div style="max-width:640px;margin:0 auto;padding:32px 20px;">
                <div style="background:linear-gradient(135deg,#0f172a,#1e293b);border-radius:24px;padding:32px;color:#f8fafc;">
                  <p style="margin:0 0 12px;font-size:12px;letter-spacing:0.24em;text-transform:uppercase;color:#7dd3fc;">PulseRoom</p>
                  <h1 style="margin:0 0 16px;font-size:32px;line-height:1.05;">Secure email confirmation</h1>
                  <p style="margin:0;font-size:16px;line-height:1.65;color:rgba(248,250,252,0.9);">Hello %s,</p>
                  <p style="margin:16px 0 0;font-size:16px;line-height:1.65;color:rgba(248,250,252,0.9);">%s</p>
                </div>
                <div style="background:#ffffff;border:1px solid #dbe4f0;border-radius:24px;padding:32px;margin-top:20px;box-shadow:0 20px 40px rgba(15,23,42,0.08);">
                  <p style="margin:0 0 20px;font-size:15px;line-height:1.7;color:#334155;">
                    Click the secure button below. The link points back to PulseRoom so we can verify that this email address is under your control before we authenticate the session.
                  </p>
                  <p style="margin:0 0 24px;">
                    <a href="%s" style="display:inline-block;background:#0ea5e9;color:#ffffff;text-decoration:none;font-weight:700;padding:14px 22px;border-radius:999px;">%s</a>
                  </p>
                  <p style="margin:0;font-size:13px;line-height:1.7;color:#64748b;">
                    This secure link expires on <strong>%s</strong>.
                  </p>
                  <p style="margin:20px 0 0;font-size:13px;line-height:1.7;color:#64748b;word-break:break-word;">
                    If the button does not open, copy this secure link into your browser:<br>
                    <a href="%s" style="color:#0284c7;">%s</a>
                  </p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(escapedName, escapedIntro, escapedActionLink, escapedActionLabel, humanExpiry, escapedActionLink, escapedActionLink);
    }

    private String html(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String normalizeLoginPath(String loginPath) {
        if (!StringUtils.hasText(loginPath)) {
            return "/login";
        }
        return loginPath.startsWith("/") ? loginPath : "/" + loginPath;
    }
}

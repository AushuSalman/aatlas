package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proving an address before an account is created for it.
 *
 * <p>Start mails a six-digit code; confirm checks it; signup then consumes the verified
 * challenge, so an account can only ever be created for an address somebody could read.
 * The code is sent synchronously: the screen says "check your inbox", and that should
 * only be said once the mail server has accepted the message.
 *
 * <p>Abuse is bounded three ways - per connection by {@link AuthRateLimiter}, per address
 * by a cap on codes an hour, and per challenge by the resend backoff and five attempts -
 * because every start sends a real mail and a mail sender's reputation is easy to lose.
 */
@Service
class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    /** Wait before each successive resend. The last value repeats. */
    private static final long[] RESEND_BACKOFF_SECONDS = {30, 60, 120, 300};
    /** Codes one address may be sent in an hour, across every challenge. */
    private static final int MAX_CODES_PER_ADDRESS_PER_HOUR = 6;
    /** How long a verified address stays good for signup: time to choose a password and a workspace. */
    static final Duration VERIFIED_WINDOW = Duration.ofHours(1);

    private final EmailVerificationRepository verifications;
    private final UserAccountRepository users;
    private final AuthRateLimiter rateLimiter;
    private final Mailer mailer;
    private final AatlasClock clock;
    private final SecureRandom random = new SecureRandom();

    EmailVerificationService(
            EmailVerificationRepository verifications,
            UserAccountRepository users,
            AuthRateLimiter rateLimiter,
            Mailer mailer,
            AatlasClock clock) {
        this.verifications = verifications;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.mailer = mailer;
        this.clock = clock;
    }

    /** What the client may know about a challenge. Never the code. Times are epoch milliseconds. */
    record ChallengeView(UUID id, String maskedEmail, long expiresAt, long resendAvailableAt, int attemptsLeft) {

        static ChallengeView of(EmailVerificationEntity c) {
            return new ChallengeView(
                    c.getId(),
                    mask(c.getEmail()),
                    c.getExpiresAt().toEpochMilli(),
                    c.getResendAvailableAt().toEpochMilli(),
                    c.getAttemptsLeft());
        }
    }

    @Transactional
    ChallengeView start(String email, String fullName, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.EMAIL_VERIFY_SEND, clientIp);
        Instant now = clock.now();
        String trimmed = email.strip();
        String normalised = UserAccount.normalise(trimmed);

        // Same answer signup gives, and for the same reason; see SignupService#emailTaken.
        if (users.existsByEmailNormalised(normalised)) {
            throw ApiException.conflict("email_taken", "That email address already has an account. Sign in instead.");
        }
        long recent = verifications.countByEmailNormalisedAndCreatedAtAfter(normalised, now.minus(Duration.ofHours(1)));
        if (recent >= MAX_CODES_PER_ADDRESS_PER_HOUR) {
            throw rateLimited("Too many codes have been sent to this address. Try again in an hour.", 3600);
        }

        EmailVerificationEntity challenge = new EmailVerificationEntity(trimmed, normalised, clientIp);
        String code = sixDigits();
        challenge.issue(hash(normalised, code), now.plus(CODE_TTL), MAX_ATTEMPTS, now.plusSeconds(RESEND_BACKOFF_SECONDS[0]));
        challenge = verifications.saveAndFlush(challenge);

        send(challenge, fullName, code);
        log.info("Verification code sent for challenge {}", challenge.getId());
        return ChallengeView.of(challenge);
    }

    @Transactional
    ChallengeView resend(UUID challengeId, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.EMAIL_VERIFY_SEND, clientIp);
        Instant now = clock.now();
        EmailVerificationEntity challenge = open(challengeId);

        if (challenge.getResendAvailableAt().isAfter(now)) {
            long wait = Math.max(1, Duration.between(now, challenge.getResendAvailableAt()).toSeconds());
            throw rateLimited("Wait %ds before asking for another code.".formatted(wait), wait);
        }
        long recent = verifications.countByEmailNormalisedAndCreatedAtAfter(
                challenge.getEmailNormalised(), now.minus(Duration.ofHours(1)));
        if (recent + challenge.getResendCount() >= MAX_CODES_PER_ADDRESS_PER_HOUR) {
            throw rateLimited("Too many codes have been sent to this address. Try again in an hour.", 3600);
        }

        challenge.recordResend();
        String code = sixDigits();
        int step = Math.min(challenge.getResendCount(), RESEND_BACKOFF_SECONDS.length - 1);
        challenge.issue(hash(challenge.getEmailNormalised(), code), now.plus(CODE_TTL), MAX_ATTEMPTS,
                now.plusSeconds(RESEND_BACKOFF_SECONDS[step]));
        challenge = verifications.saveAndFlush(challenge);

        send(challenge, null, code);
        log.info("Verification code resent for challenge {} ({} resends)", challenge.getId(), challenge.getResendCount());
        return ChallengeView.of(challenge);
    }

    /**
     * Checks a code. Not rolled back on refusal: a wrong guess has to cost an attempt even
     * though the request fails, or the five-attempt limit would not limit anything.
     */
    @Transactional(noRollbackFor = ApiException.class)
    void confirm(UUID challengeId, String code, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.EMAIL_VERIFY_CONFIRM, clientIp);
        Instant now = clock.now();
        EmailVerificationEntity challenge = verifications.findById(challengeId)
                .filter(c -> c.getConsumedAt() == null)
                .orElseThrow(EmailVerificationService::expiredChallenge);

        if (challenge.getVerifiedAt() != null) {
            return; // Already confirmed - a double submit is not an error.
        }
        if (challenge.isExpired(now)) {
            throw ApiException.badRequest("code_expired", "That code has expired. Send a new one.");
        }
        if (challenge.getAttemptsLeft() <= 0) {
            throw tooManyAttempts();
        }

        byte[] presented = hash(challenge.getEmailNormalised(), code.strip());
        if (!MessageDigest.isEqual(presented, challenge.getCodeHash())) {
            challenge.recordWrongCode();
            verifications.saveAndFlush(challenge);
            int left = challenge.getAttemptsLeft();
            if (left <= 0) {
                throw tooManyAttempts();
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_code",
                    "That code is not right. %d %s left.".formatted(left, left == 1 ? "try" : "tries"),
                    Map.of("attemptsLeft", left));
        }

        challenge.markVerified(now);
        verifications.saveAndFlush(challenge);
        log.info("Email verified for challenge {}", challenge.getId());
    }

    /**
     * Spends a verified challenge on the signup it unlocks. Joins the signup's transaction,
     * so a signup that fails leaves the verification usable for the retry.
     */
    @Transactional
    void consumeForSignup(UUID challengeId, String emailNormalised) {
        Instant now = clock.now();
        EmailVerificationEntity challenge = challengeId == null ? null : verifications.findById(challengeId).orElse(null);
        if (challenge == null
                || challenge.getVerifiedAt() == null
                || challenge.getConsumedAt() != null
                || !challenge.getEmailNormalised().equals(emailNormalised)
                || challenge.getVerifiedAt().plus(VERIFIED_WINDOW).isBefore(now)) {
            throw ApiException.badRequest("email_not_verified",
                    "Verify your email address before creating the account.");
        }
        challenge.markConsumed(now);
        verifications.saveAndFlush(challenge);
    }

    private EmailVerificationEntity open(UUID challengeId) {
        return verifications.findById(challengeId)
                .filter(c -> c.getVerifiedAt() == null && c.getConsumedAt() == null)
                .orElseThrow(EmailVerificationService::expiredChallenge);
    }

    private void send(EmailVerificationEntity challenge, String fullName, String code) {
        try {
            mailer.sendVerificationCode(challenge.getEmail(), fullName, code, challenge.getExpiresAt());
        } catch (RuntimeException ex) {
            // Rolls the challenge back with it: a code nobody received must not count against the address.
            log.error("Could not send the verification mail for challenge {}", challenge.getId(), ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_failed",
                    "We could not send a code to that address right now. Try again in a moment.");
        }
    }

    private String sixDigits() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** Salted with the address so the same code on two challenges does not share a digest. */
    private static byte[] hash(String emailNormalised, String code) {
        return TokenService.sha256(emailNormalised + ":" + code);
    }

    private static ApiException expiredChallenge() {
        return ApiException.badRequest("code_expired", "That verification has expired. Go back and enter your email again.");
    }

    private static ApiException tooManyAttempts() {
        return ApiException.badRequest("too_many_attempts", "Too many wrong codes. Send a new one to try again.");
    }

    private static ApiException rateLimited(String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message,
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    /** "al••••@kestrelsupply.com": enough to recognise, not enough to harvest. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        int shown = Math.min(2, Math.max(1, local.length() - 1));
        int hidden = Math.max(2, Math.min(6, local.length() - shown));
        return local.substring(0, shown) + "•".repeat(hidden) + email.substring(at);
    }
}

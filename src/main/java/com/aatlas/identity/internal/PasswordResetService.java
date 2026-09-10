package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot and reset.
 *
 * <p>Forgot always answers 202, whether or not the address has an account: the mail is
 * the only channel that says anything, and the response must not become an oracle for
 * which addresses exist. (Signup does tell the truth about a taken address; the
 * difference is that a signup form has a person who needs the answer, and a forgot form
 * has one who already knows theirs.)
 *
 * <p>Reset is the one place a password changes without the old one, so it also signs the
 * account out everywhere: whoever prompted the reset may have been the person who lost
 * control of the old password, and any session that password opened should end with it.
 */
@Service
class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** One hour: long enough to find the mail, short enough that a stale link is not a standing risk. */
    static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final UserAccountRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final Mailer mailer;
    private final AatlasClock clock;

    PasswordResetService(
            UserAccountRepository users,
            PasswordResetTokenRepository resetTokens,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            Mailer mailer,
            AatlasClock clock) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.mailer = mailer;
        this.clock = clock;
    }

    @Transactional
    void forgot(String email, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.PASSWORD_FORGOT, clientIp);
        Instant now = clock.now();

        Optional<UserAccount> found = users.findByEmailNormalised(UserAccount.normalise(email));
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE) {
            // Silent on purpose; see the class comment.
            log.info("Password reset requested for an address with no active account");
            return;
        }
        UserAccount user = found.get();

        TokenService.OpaqueToken token = tokenService.issueOpaqueToken(TOKEN_TTL);
        resetTokens.save(new PasswordResetTokenEntity(
                user.getTenantId(), user.getId(), token.hash(), token.expiresAt(), clientIp));
        mailer.sendPasswordReset(user.getEmail(), user.getFullName(), token.value(), token.expiresAt());
        log.info("Password reset token issued for user {}", user.getId());
    }

    @Transactional
    void reset(String presented, String newPassword, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.PASSWORD_RESET, clientIp);
        Instant now = clock.now();

        PasswordResetTokenEntity token = resetTokens.findByTokenHash(TokenService.sha256(presented.strip()))
                .filter(t -> t.isLive(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_reset_token",
                        "That reset link is not valid or has expired. Request a new one."));

        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.badRequest("invalid_reset_token",
                        "That reset link is not valid or has expired. Request a new one."));

        passwordPolicy.check(newPassword, user.getEmailNormalised());

        user.changePassword(passwordEncoder.encode(newPassword));
        users.saveAndFlush(user);
        token.markUsed(now);
        resetTokens.saveAndFlush(token);

        int revoked = refreshTokens.revokeAllForUser(user.getId(), now, "password_reset");
        log.info("Password reset for user {}; {} session(s) signed out", user.getId(), revoked);
    }
}

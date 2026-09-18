package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, refresh and sign-out: the life of a session after signup.
 *
 * <p>{@code noRollbackFor = ApiException} on the two mutating methods is deliberate and
 * worth understanding. A wrong password must leave its mark on {@code failed_login_count}
 * even though the request ends in a 401, and a reused refresh token must revoke the whole
 * family even though the request ends in a 401. Both are "the request failed and the
 * failure is the record", which a rolled-back transaction would erase.
 */
@Service
class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /**
     * A BCrypt hash of nothing in particular, compared against when the email is unknown so
     * an absent account costs the same tenth of a second as a wrong password. Without it,
     * response time would say which addresses have accounts.
     */
    private static final String DUMMY_HASH = "$2a$12$K2nQyQ3VhZ1zJf4VbG7f4eYh6c0a1QWx8yqz5yU4E3o0m7zX1p9Ne";

    /** How far a logout follows {@code replaced_by} before giving up. Chains are short; this is a guard. */
    private static final int MAX_CHAIN = 64;

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final SessionViews sessions;
    private final AatlasClock clock;

    SessionService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            SessionViews sessions,
            AatlasClock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Email and password in, tokens and session out.
     *
     * <p>One message for a wrong email and a wrong password, and the same cost for both,
     * so neither the words nor the timing say which addresses have accounts.
     */
    @Transactional(noRollbackFor = ApiException.class)
    AuthResponse login(LoginRequest request, String clientIp, String userAgent) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.LOGIN, clientIp);
        Instant now = clock.now();

        Optional<UserAccount> found = users.findByEmailNormalised(UserAccount.normalise(request.email()));
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw invalidCredentials();
        }
        UserAccount user = found.get();

        if (user.isLocked(now)) {
            long wait = Math.max(1, Duration.between(now, user.getLockedUntil()).toSeconds());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "account_locked",
                    "Too many failed sign-ins. Try again in " + Math.max(1, wait / 60) + " minutes.",
                    Map.of("retryAfterSeconds", wait));
        }
        if (user.getStatus() == UserStatus.INVITED) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new ApiException(HttpStatus.FORBIDDEN, "invitation_pending",
                    "You have been invited but have not set a password yet. Open the invitation email to finish.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "account_inactive",
                    "This account has been suspended or removed. Contact your workspace admin.");
        }

        if (user.getPasswordHash() == null) {
            // Created with Google or Apple and never given a password. Not a failed guess, so no lockout.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "password_not_set",
                    "This account signs in with Google or Apple. Use that button, or reset your password to set one.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean locked = user.recordFailedLogin(now);
            users.saveAndFlush(user);
            if (locked) {
                log.warn("Account {} locked after repeated failed sign-ins", user.getId());
            }
            throw invalidCredentials();
        }

        return issue(user, clientIp, userAgent, now);
    }

    /**
     * A session for a person a provider has already authenticated. The same checks as a
     * password sign-in, minus the password.
     */
    @Transactional
    AuthResponse signIn(UserAccount user, String clientIp, String userAgent) {
        Instant now = clock.now();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "account_inactive",
                    "This account is not active. Ask your administrator.");
        }
        return issue(user, clientIp, userAgent, now);
    }

    private AuthResponse issue(UserAccount user, String clientIp, String userAgent, Instant now) {
        user.recordSuccessfulLogin(now);
        users.saveAndFlush(user);

        TokenService.AccessToken access = tokenService.issueAccessToken(
                user.getTenantId(), user.getId(), user.getEmail(), user.getSeatRole());
        TokenService.OpaqueToken refresh = tokenService.issueRefreshToken();
        refreshTokens.save(new RefreshTokenEntity(
                user.getTenantId(), user.getId(), refresh.hash(), now, refresh.expiresAt(), userAgent, clientIp));

        log.info("Login: tenant={} user={} seat={}", user.getTenantId(), user.getId(), user.getSeatRole());
        return AuthResponse.of(access, refresh.value(), sessions.session(user, now, false));
    }

    /**
     * Rotation. The presented token is spent, a fresh one is issued, and the two are
     * chained. Presenting a spent token means it leaked: the honest client and the thief
     * are indistinguishable at that point, so every token the user holds is revoked and
     * both are made to sign in again.
     */
    @Transactional(noRollbackFor = ApiException.class)
    AuthResponse refresh(String presented, String clientIp, String userAgent) {
        Instant now = clock.now();
        RefreshTokenEntity token = refreshTokens.lockByTokenHash(TokenService.sha256(presented.strip()))
                .orElseThrow(() -> unauthorized("invalid_refresh_token", "That refresh token is not one we issued."));

        if (token.isSpent()) {
            int revoked = refreshTokens.revokeAllForUser(token.getUserId(), now, "refresh_reused");
            log.warn("Refresh token reuse for user {}: revoked {} token(s)", token.getUserId(), revoked);
            throw unauthorized("refresh_reused",
                    "That refresh token was already used. Every session for this account has been signed out.");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw unauthorized("refresh_expired", "That session has expired. Sign in again.");
        }

        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> unauthorized("invalid_refresh_token", "That account no longer exists."));
        if (!user.isUsable(now)) {
            throw unauthorized("account_inactive", "This account cannot sign in right now.");
        }

        TokenService.OpaqueToken next = tokenService.issueRefreshToken();
        RefreshTokenEntity successor = refreshTokens.save(new RefreshTokenEntity(
                user.getTenantId(), user.getId(), next.hash(), now, next.expiresAt(), userAgent, clientIp));
        token.markUsed(now, successor.getId());
        refreshTokens.saveAndFlush(token);

        TokenService.AccessToken access = tokenService.issueAccessToken(
                user.getTenantId(), user.getId(), user.getEmail(), user.getSeatRole());
        return AuthResponse.of(access, next.value(), sessions.session(user, now, false));
    }

    /**
     * Revokes the presented token and, if it was already rotated, whatever it was rotated
     * into - the client may be signing out with a token one step behind. Unknown tokens
     * are a 204 too: sign-out is idempotent and there is nothing useful to say about a
     * token that never existed.
     */
    @Transactional
    void logout(String presented) {
        Instant now = clock.now();
        Optional<RefreshTokenEntity> current = refreshTokens.findByTokenHash(TokenService.sha256(presented.strip()));
        int hops = 0;
        while (current.isPresent() && hops++ < MAX_CHAIN) {
            RefreshTokenEntity token = current.get();
            token.revoke(now, "logout");
            refreshTokens.save(token);
            current = token.getReplacedBy() == null ? Optional.empty() : refreshTokens.findById(token.getReplacedBy());
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials",
                "That email and password do not match.");
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}

package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign in from an emailed link, for someone who already has an account.
 *
 * <p>The mailbox is the proof: whoever can open the link owns the address the account is
 * registered to, which is the same trust a password reset rests on. So the flow is the reset
 * flow with a session at the end instead of a password form - a random token, only its
 * digest stored, one use, and a short life.
 *
 * <p>Asking for a link is silent about whether the address has an account: the response is
 * the same either way, and only the mail says more. Otherwise this endpoint would tell anyone
 * which addresses are registered here.
 */
@Service
class MagicLinkService {

    private static final Logger log = LoggerFactory.getLogger(MagicLinkService.class);

    /** Fifteen minutes: long enough to open the mail, short enough that a forwarded link is not a standing key. */
    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final UserAccountRepository users;
    private final MagicLinkTokenRepository links;
    private final SessionService sessions;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final Mailer mailer;
    private final AatlasClock clock;

    MagicLinkService(
            UserAccountRepository users,
            MagicLinkTokenRepository links,
            SessionService sessions,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            Mailer mailer,
            AatlasClock clock) {
        this.users = users;
        this.links = links;
        this.sessions = sessions;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.mailer = mailer;
        this.clock = clock;
    }

    @Transactional
    void start(String email, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.MAGIC_LINK_START, clientIp);

        Optional<UserAccount> found = users.findByEmailNormalised(UserAccount.normalise(email));
        if (found.isEmpty() || found.get().getStatus() != UserStatus.ACTIVE) {
            // Silent on purpose; see the class comment.
            log.info("Magic link requested for an address with no active account");
            return;
        }
        UserAccount user = found.get();

        TokenService.OpaqueToken token = tokenService.issueOpaqueToken(TOKEN_TTL);
        links.save(new MagicLinkTokenEntity(
                user.getTenantId(), user.getId(), token.hash(), token.expiresAt(), clientIp));
        mailer.sendMagicLink(user.getEmail(), user.getFullName(), token.value(), token.expiresAt());
        log.info("Magic link issued for user {}", user.getId());
    }

    /** The link is spent before the sign-in is attempted: a link that failed once must not be retried. */
    @Transactional
    AuthResponse consume(String presented, String clientIp, String userAgent) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.MAGIC_LINK_CONSUME, clientIp);
        Instant now = clock.now();

        MagicLinkTokenEntity link = links.findByTokenHash(TokenService.sha256(presented.strip()))
                .filter(t -> t.isLive(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_magic_link",
                        "That sign-in link is not valid or has expired. Request a new one."));
        link.markUsed(now);
        links.saveAndFlush(link);

        UserAccount user = users.findById(link.getUserId())
                .orElseThrow(() -> ApiException.badRequest("invalid_magic_link",
                        "That sign-in link is not valid or has expired. Request a new one."));

        AuthResponse response = sessions.signIn(user, clientIp, userAgent);
        log.info("Magic link sign-in for user {}", user.getId());
        return response;
    }
}

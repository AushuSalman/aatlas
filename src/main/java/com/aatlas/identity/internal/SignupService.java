package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.identity.UserSignedUp;
import com.aatlas.policy.PolicyReader;
import com.aatlas.tenant.TenantDirectory;
import com.aatlas.tenant.TenantProvisioning;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating an account.
 *
 * <p>One transaction writes four rows - the company, its settings, its first user, and the
 * refresh token that user leaves with - and enrols the {@code UserSignedUp} event in the
 * same commit through the outbox. Either all of it happened or none of it did; there is no
 * state in which a company exists that nobody can sign in to.
 *
 * <p>Everything that does not have to happen before the response is deliberately not in
 * this method. Sample data, cache warming and the welcome mail all hang off the event,
 * because none of them is something the person is waiting for and a slow mail server must
 * not be able to fail an account creation that already succeeded.
 */
@Service
class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final TenantProvisioning tenants;
    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final PolicyReader policy;
    private final SessionViews sessions;
    private final DomainEventPublisher events;
    private final AatlasClock clock;

    SignupService(
            TenantProvisioning tenants,
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            PolicyReader policy,
            SessionViews sessions,
            DomainEventPublisher events,
            AatlasClock clock) {
        this.tenants = tenants;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.policy = policy;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates a company and its first user, and signs that user in.
     *
     * @param clientIp for the rate limiter and the token's audit trail; may be null
     * @param userAgent recorded against the refresh token so a person can recognise a
     *     device in the "signed-in devices" list; may be null
     */
    @Transactional
    AuthResponse signUp(SignupRequest request, String clientIp, String userAgent) {
        // Before BCrypt, not after: a rejected caller should cost a map lookup rather than
        // a tenth of a second of CPU.
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.SIGNUP, clientIp);

        String email = request.email().strip();
        String emailNormalised = UserAccount.normalise(email);
        passwordPolicy.check(request.password(), emailNormalised);

        // Advisory: the unique index is what actually decides. Checking first turns the
        // ordinary case - someone forgot they had an account - into a clean 409 instead of
        // a constraint violation that has to be unpicked from a driver exception.
        if (users.existsByEmailNormalised(emailNormalised)) {
            throw emailTaken();
        }

        TenantProvisioning.TenantView tenant = tenants.provision(
                new TenantProvisioning.NewTenant(request.company(), request.country()));

        // The title comes from role_policy, never from the form: a new tenant is on the
        // platform defaults, so this is the persona's title as the seat picker showed it.
        String title = policy.personaFor(tenant.id(), request.role().wireValue()).title();

        UserAccount user = new UserAccount(
                tenant.id(),
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().strip().replaceAll("\\s+", " "),
                request.role(),
                title);

        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Two requests for the same address raced past the check above and the index
            // caught the loser. The caller sees the same 409 either way.
            log.debug("Signup lost the race on the email unique index", ex);
            throw emailTaken();
        }

        Instant now = clock.now();
        TokenService.AccessToken accessToken =
                tokenService.issueAccessToken(tenant.id(), user.getId(), user.getEmail(), user.getSeatRole());
        TokenService.OpaqueToken refreshToken = tokenService.issueRefreshToken();

        refreshTokens.save(new RefreshTokenEntity(
                tenant.id(),
                user.getId(),
                refreshToken.hash(),
                now,
                refreshToken.expiresAt(),
                userAgent,
                clientIp));

        events.publish(new UserSignedUp(tenant.id(), user.getId(), user.getEmail(), user.getSeatRole(), now));

        // The address is not logged: it is personal data, and the ids are enough to follow
        // the account through the system.
        log.info("Signup complete: tenant={} user={} seat={}", tenant.id(), user.getId(), user.getSeatRole());

        TenantDirectory.TenantInfo info = new TenantDirectory.TenantInfo(tenant.id(), tenant.name(), tenant.slug(),
                "ACTIVE", tenant.country(), tenant.tradingCurrency(), now);
        return AuthResponse.of(accessToken, refreshToken.value(), sessions.session(user, info, now, true));
    }

    /**
     * Signup tells the truth about a taken address.
     *
     * <p>This does leak whether an address has an account here, and the alternative -
     * accepting the request and sending "someone tried to sign up with your address" by
     * mail - is the more private design. It is not the one chosen: this is a B2B product
     * where colleagues share a domain and being told "that address already has a seat, ask
     * your admin" is the difference between finishing and giving up. The enumeration risk
     * is real but small against a corporate directory an attacker can usually guess anyway.
     */
    private static ApiException emailTaken() {
        return ApiException.conflict(
                "email_taken", "That email address already has an account. Sign in instead.");
    }
}

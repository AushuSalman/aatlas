package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google and Apple, from a verified identity to an account.
 *
 * <p>Three steps, each its own call so the browser can show the right screen in between:
 * exchange (code in, verified identity and a ticket out), sign-in (ticket in, a session if
 * an account is linked), and signup (ticket in place of a password, in {@link SignupService}).
 *
 * <p>Linking is on the provider's subject. The one exception is an existing account whose
 * address the provider is authoritative for - a Gmail or Workspace address at Google, a
 * non-relay address at Apple: signing in there links the identity rather than turning the
 * person away, because the provider has just proven the same thing a password reset would.
 */
@Service
class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    static final Duration TICKET_TTL = Duration.ofMinutes(30);

    private final OidcVerifier verifier;
    private final SsoTicketRepository tickets;
    private final UserIdentityRepository identities;
    private final UserAccountRepository users;
    private final TokenService tokenService;
    private final SessionService sessions;
    private final AuthRateLimiter rateLimiter;
    private final AatlasClock clock;

    SsoService(
            OidcVerifier verifier,
            SsoTicketRepository tickets,
            UserIdentityRepository identities,
            UserAccountRepository users,
            TokenService tokenService,
            SessionService sessions,
            AuthRateLimiter rateLimiter,
            AatlasClock clock) {
        this.verifier = verifier;
        this.tickets = tickets;
        this.identities = identities;
        this.users = users;
        this.tokenService = tokenService;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** The frontend's {@code SsoIdentity}, plus the ticket that stands for it. */
    record IdentityView(
            String provider,
            String subject,
            String email,
            boolean emailVerified,
            String fullName,
            boolean isPrivateRelay,
            String ticket) {
    }

    @Transactional
    IdentityView exchange(String providerWire, SsoRequests.Exchange request, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.SSO, clientIp);
        SsoProvider provider = SsoProvider.fromWire(providerWire);
        OidcVerifier.VerifiedIdentity identity = verifier.exchange(
                provider, request.code(), request.codeVerifier(), request.nonce(), request.redirectUri());

        // Apple's name arrives in the form post, not the token; take the browser's word for a
        // display name only, which is all it is.
        if (identity.fullName() == null && request.fullName() != null && !request.fullName().isBlank()) {
            identity = new OidcVerifier.VerifiedIdentity(identity.provider(), identity.subject(), identity.email(),
                    identity.emailVerified(), request.fullName().strip(), identity.privateRelay(), identity.emailAuthoritative());
        }

        TokenService.OpaqueToken ticket = tokenService.issueOpaqueToken(TICKET_TTL);
        tickets.save(new SsoTicketEntity(ticket.hash(), identity, ticket.expiresAt(), clientIp));
        log.info("{} identity verified", provider);

        return new IdentityView(provider.wireValue(), identity.subject(), identity.email(), identity.emailVerified(),
                identity.fullName() == null ? "" : identity.fullName(), identity.privateRelay(), ticket.value());
    }

    /**
     * Signs in the account linked to the ticket's identity. 404 {@code sso_not_linked} when
     * there is none - the answer that sends the browser on to signup - and the ticket is left
     * unspent so signup can use it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    AuthResponse login(String presentedTicket, String clientIp, String userAgent) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.SSO, clientIp);
        Instant now = clock.now();
        SsoTicketEntity ticket = liveTicket(presentedTicket, now);
        OidcVerifier.VerifiedIdentity identity = ticket.identity();

        Optional<UserAccount> user = identities.findByProviderAndSubject(identity.provider().wireValue(), identity.subject())
                .flatMap(link -> users.findById(link.getUserId()));

        if (user.isEmpty() && identity.emailAuthoritative()) {
            user = users.findByEmailNormalised(UserAccount.normalise(identity.email()));
            user.ifPresent(existing -> {
                if (!identities.existsByUserIdAndProvider(existing.getId(), identity.provider().wireValue())) {
                    identities.save(new UserIdentityEntity(existing.getTenantId(), existing.getId(),
                            identity.provider(), identity.subject(), identity.email()));
                    log.info("Linked {} identity to existing user {}", identity.provider(), existing.getId());
                }
            });
        }

        if (user.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "sso_not_linked",
                    "No Aatlas account is linked to this " + identity.provider().label() + " account yet.");
        }

        ticket.markConsumed(now);
        tickets.saveAndFlush(ticket);
        return sessions.signIn(user.get(), clientIp, userAgent);
    }

    /** Spends a ticket on signup, inside the signup's transaction. */
    @Transactional
    OidcVerifier.VerifiedIdentity consumeForSignup(String presentedTicket) {
        Instant now = clock.now();
        SsoTicketEntity ticket = liveTicket(presentedTicket, now);
        OidcVerifier.VerifiedIdentity identity = ticket.identity();
        if (identities.findByProviderAndSubject(identity.provider().wireValue(), identity.subject()).isPresent()) {
            throw ApiException.conflict("sso_already_linked",
                    "This " + identity.provider().label() + " account already has an Aatlas account. Sign in instead.");
        }
        ticket.markConsumed(now);
        tickets.saveAndFlush(ticket);
        return identity;
    }

    /** Called by signup once the user row exists. */
    void link(UserAccount user, OidcVerifier.VerifiedIdentity identity) {
        identities.save(new UserIdentityEntity(user.getTenantId(), user.getId(),
                identity.provider(), identity.subject(), identity.email()));
    }

    private SsoTicketEntity liveTicket(String presented, Instant now) {
        return tickets.findByTokenHash(TokenService.sha256(presented.strip()))
                .filter(t -> t.isLive(now))
                .orElseThrow(() -> ApiException.badRequest("sso_ticket_invalid",
                        "That sign-in has expired. Start again with Google or Apple."));
    }
}

package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.identity.SeatRole;
import com.aatlas.policy.PolicyReader;
import com.aatlas.tenant.TenantDirectory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Users screen: inviting people, setting their access, suspending and removing them,
 * and the invitation a new person accepts to set their password.
 *
 * <p>The rules are the frontend's ({@code users.ts}), enforced again here because a
 * disabled button is a courtesy and this is the control:
 * <ul>
 *   <li>Only a Super Admin or an Admin manages users.
 *   <li>A Super Admin manages Admins and Members; an Admin manages Members only, and
 *       cannot grant access they do not hold themselves.
 *   <li>Nobody changes their own access - the one way a workspace ends up with nobody
 *       able to run it - and Super Admin is never granted from here.
 * </ul>
 *
 * <p>The users table is not under row-level security (sign-in needs it before a tenant is
 * known), so every query here names the tenant explicitly.
 */
@Service
class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    static final Duration INVITE_TTL = Duration.ofDays(7);

    private static final Map<String, String> MODULE_LABEL = Map.of(
            "sell", "Sell", "buy", "Buy", "suppliers", "Suppliers", "insights", "Insights",
            "stores", "Branches", "products", "Products", "history", "History");

    private final UserAccountRepository users;
    private final UserInvitationRepository invitations;
    private final UserAuditRepository audit;
    private final RefreshTokenRepository refreshTokens;
    private final PolicyReader policy;
    private final TenantDirectory tenants;
    private final TokenService tokenService;
    private final Mailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionService sessions;
    private final UserAccessGuard accessGuard;
    private final AuthRateLimiter rateLimiter;
    private final AatlasClock clock;

    UserManagementService(
            UserAccountRepository users,
            UserInvitationRepository invitations,
            UserAuditRepository audit,
            RefreshTokenRepository refreshTokens,
            PolicyReader policy,
            TenantDirectory tenants,
            TokenService tokenService,
            Mailer mailer,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            SessionService sessions,
            UserAccessGuard accessGuard,
            AuthRateLimiter rateLimiter,
            AatlasClock clock) {
        this.users = users;
        this.invitations = invitations;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.policy = policy;
        this.tenants = tenants;
        this.tokenService = tokenService;
        this.mailer = mailer;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.sessions = sessions;
        this.accessGuard = accessGuard;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    // -- What the screen reads -------------------------------------------------------------------

    /** The frontend's {@code DirectoryRecord}, field for field. */
    record UserRecord(
            UUID id,
            String name,
            String email,
            String persona,
            String workspaceRole,
            UserPermissions permissions,
            String status,
            Instant createdAt,
            Instant invitedAt,
            String invitedBy,
            Instant lastActiveAt) {
    }

    /** The frontend's {@code AuditEntry}. */
    record AuditView(UUID id, Instant at, String actor, String action, String target, String detail) {
    }

    /** What the accept page shows before the person sets a password. */
    record InvitationView(String email, String name, String company, String invitedBy, Instant expiresAt) {
    }

    private record Actor(UserAccount user, UserPermissions permissions) {
        WorkspaceRole level() {
            return user.getWorkspaceRole();
        }
    }

    @Transactional(readOnly = true)
    List<UserRecord> list() {
        Actor actor = requireAdmin();
        List<UserAccount> people = users.findByTenantIdAndStatusNot(actor.user().getTenantId(), UserStatus.DISABLED);
        Map<UUID, String> names = people.stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return people.stream()
                .sorted(Comparator.comparing(UserAccount::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> record(u, names))
                .toList();
    }

    @Transactional(readOnly = true)
    List<AuditView> auditLog() {
        Actor actor = requireAdmin();
        return audit.findTop200ByTenantIdOrderByCreatedAtDesc(actor.user().getTenantId()).stream()
                .map(a -> new AuditView(a.getId(), a.getCreatedAt(), a.getActorName(), a.getAction(), a.getTarget(), a.getDetail()))
                .toList();
    }

    // -- Changes --------------------------------------------------------------------------------

    @Transactional
    UserRecord invite(UserRequests.Invite input) {
        Actor actor = requireAdmin();
        UUID tenantId = actor.user().getTenantId();
        Instant now = clock.now();

        String name = cleanName(input.name());
        String email = input.email().strip();
        String emailNormalised = UserAccount.normalise(email);
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")) {
            throw fieldError("email", "Enter a valid email address.");
        }
        SeatRole seat = seat(input.persona());
        WorkspaceRole level = assignable(actor, input.workspaceRole());
        UserPermissions permissions = checkedPermissions(actor, tenantId, seat, input.permissions());
        String title = policy.personaFor(tenantId, seat.wireValue()).title();

        UserAccount user = users.findByEmailNormalised(emailNormalised).orElse(null);
        if (user != null) {
            if (!user.getTenantId().equals(tenantId)) {
                throw new ApiException(HttpStatus.CONFLICT, "email_taken",
                        "That email already has an Aatlas account in another workspace.", Map.of("field", "email"));
            }
            if (user.getStatus() != UserStatus.DISABLED) {
                throw new ApiException(HttpStatus.CONFLICT, "email_taken",
                        "Someone in this workspace already uses that email.", Map.of("field", "email"));
            }
            // Removed earlier and invited back: same row, fresh access, no old password.
            user.reinvite(name, seat, title, level, permissions, actor.user().getId(), now);
        } else {
            user = UserAccount.invited(tenantId, email, name, seat, title, level, permissions, actor.user().getId(), now);
        }
        user = users.saveAndFlush(user);

        sendInvitation(user, actor.user(), now);
        record(actor, "invited", user.getEmail(),
                "Invited as %s · %s · %s".formatted(level.label(), title, summary(effective(tenantId, user))));
        log.info("User {} invited to tenant {} by {}", user.getId(), tenantId, actor.user().getId());
        return record(user, Map.of(actor.user().getId(), actor.user().getFullName()));
    }

    @Transactional
    UserRecord update(UUID id, UserRequests.Update input) {
        Actor actor = requireAdmin();
        UUID tenantId = actor.user().getTenantId();
        UserAccount target = manageable(actor, id);

        String name = cleanName(input.name());
        SeatRole seat = seat(input.persona());
        WorkspaceRole level = assignable(actor, input.workspaceRole());
        UserPermissions permissions = checkedPermissions(actor, tenantId, seat, input.permissions());

        String before = describe(tenantId, target);
        String oldName = target.getFullName();
        target.changeAccess(name, seat, policy.personaFor(tenantId, seat.wireValue()).title(), level, permissions);
        users.saveAndFlush(target);
        accessGuard.evict(target.getId());

        String after = describe(tenantId, target);
        List<String> changes = new ArrayList<>();
        if (!oldName.equals(name)) {
            changes.add("name to " + name);
        }
        if (!before.equals(after)) {
            changes.add("access to " + after);
        }
        record(actor, "updated", target.getEmail(), changes.isEmpty() ? "Saved with no changes" : "Changed " + String.join("; ", changes));
        return record(target, names(tenantId));
    }

    @Transactional
    UserRecord setSuspended(UUID id, boolean suspend) {
        Actor actor = requireAdmin();
        UserAccount target = manageable(actor, id);
        Instant now = clock.now();
        if (suspend) {
            target.suspend();
            // Signed out everywhere: the refresh tokens go now, and the access guard refuses
            // the access token they still hold on its next request.
            refreshTokens.revokeAllForUser(target.getId(), now, "suspended");
        } else {
            target.reactivate();
        }
        users.saveAndFlush(target);
        accessGuard.evict(target.getId());
        record(actor, suspend ? "suspended" : "reactivated", target.getEmail(),
                suspend ? "Suspended; signed out everywhere" : "Access restored");
        return record(target, names(actor.user().getTenantId()));
    }

    @Transactional
    UserRecord resendInvitation(UUID id) {
        Actor actor = requireAdmin();
        UserAccount target = manageable(actor, id);
        if (target.getStatus() != UserStatus.INVITED) {
            throw ApiException.conflict("not_invited", "Only pending invitations can be resent.");
        }
        Instant now = clock.now();
        target.markInvitationResent(now);
        users.saveAndFlush(target);
        sendInvitation(target, actor.user(), now);
        record(actor, "invite_resent", target.getEmail(), "Invitation email sent again");
        return record(target, names(actor.user().getTenantId()));
    }

    @Transactional
    void remove(UUID id) {
        Actor actor = requireAdmin();
        UserAccount target = manageable(actor, id);
        Instant now = clock.now();
        target.remove();
        users.saveAndFlush(target);
        refreshTokens.revokeAllForUser(target.getId(), now, "removed");
        invitations.findByUserId(target.getId()).forEach(i -> i.revoke(now));
        accessGuard.evict(target.getId());
        record(actor, "removed", target.getEmail(), "Removed " + target.getFullName() + " from the workspace");
    }

    // -- Accepting an invitation (no session yet) ----------------------------------------------

    @Transactional(readOnly = true)
    InvitationView lookup(String token, String clientIp) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.INVITATION, clientIp);
        UserInvitationEntity invitation = liveInvitation(token);
        UserAccount user = invitedUser(invitation);
        String inviter = invitation.getInvitedBy() == null ? "Your admin"
                : users.findById(invitation.getInvitedBy()).map(UserAccount::getFullName).orElse("Your admin");
        return new InvitationView(user.getEmail(), user.getFullName(),
                tenants.get(user.getTenantId()).name(), inviter, invitation.getExpiresAt());
    }

    /** Sets the password, activates the account and signs them in. */
    @Transactional
    AuthResponse accept(String token, String password, String clientIp, String userAgent) {
        rateLimiter.checkAndRecord(AuthRateLimiter.Action.INVITATION, clientIp);
        Instant now = clock.now();
        UserInvitationEntity invitation = liveInvitation(token);
        UserAccount user = invitedUser(invitation);

        passwordPolicy.check(password, user.getEmailNormalised());
        user.acceptInvitation(passwordEncoder.encode(password));
        users.saveAndFlush(user);
        invitation.markAccepted(now);
        invitations.saveAndFlush(invitation);
        accessGuard.evict(user.getId());

        audit.save(new UserAuditEntity(user.getTenantId(), user.getId(), user.getFullName(), "activated",
                user.getEmail(), "Accepted the invitation and set a password"));
        log.info("Invitation accepted: tenant={} user={}", user.getTenantId(), user.getId());
        return sessions.signIn(user, clientIp, userAgent);
    }

    // -- Rules ------------------------------------------------------------------------------------

    private Actor requireAdmin() {
        TenantContext.Actor ctx = TenantContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
        UserAccount me = users.findById(ctx.userId())
                .filter(u -> u.getTenantId().equals(ctx.tenantId()) && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
        if (me.getWorkspaceRole() == WorkspaceRole.MEMBER) {
            throw ApiException.forbidden("Only admins manage users.");
        }
        return new Actor(me, effective(me.getTenantId(), me));
    }

    /** The target, if this actor may manage them; 403 with the reason otherwise. */
    private UserAccount manageable(Actor actor, UUID id) {
        UserAccount target = users.findById(id)
                .filter(u -> u.getTenantId().equals(actor.user().getTenantId()) && u.getStatus() != UserStatus.DISABLED)
                .orElseThrow(() -> ApiException.notFound("User", id));
        String refusal = null;
        if (target.getId().equals(actor.user().getId())) {
            refusal = "You cannot change your own access. Ask another admin.";
        } else if (target.getWorkspaceRole() == WorkspaceRole.SUPER_ADMIN) {
            refusal = "The Super Admin’s access cannot be changed here.";
        } else if (actor.level() == WorkspaceRole.ADMIN && target.getWorkspaceRole() == WorkspaceRole.ADMIN) {
            refusal = "Only the Super Admin can manage other admins.";
        }
        if (refusal != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed", refusal);
        }
        return target;
    }

    private static WorkspaceRole assignable(Actor actor, String wire) {
        WorkspaceRole level = WorkspaceRole.fromWire(wire);
        boolean ok = actor.level() == WorkspaceRole.SUPER_ADMIN
                ? level != WorkspaceRole.SUPER_ADMIN
                : level == WorkspaceRole.MEMBER;
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed", "You cannot give that access level.");
        }
        return level;
    }

    /**
     * The requested access, cleaned and checked. Null when it equals the job function's
     * defaults, so a later change to the job function's policy still reaches this person.
     */
    private UserPermissions checkedPermissions(Actor actor, UUID tenantId, SeatRole seat, UserPermissions requested) {
        UserPermissions defaults = UserPermissions.of(policy.personaFor(tenantId, seat.wireValue()));
        UserPermissions wanted = requested == null ? defaults : requested;

        for (String m : wanted.modules()) {
            if (!UserPermissions.KNOWN.contains(m)) {
                throw fieldError("modules", "Unknown module: " + m + ".");
            }
        }
        List<String> modules = UserPermissions.MODULE_ORDER.stream().filter(wanted.modules()::contains).toList();
        if (modules.isEmpty()) {
            throw fieldError("modules", "Give at least one module, or suspend the user instead.");
        }
        if (wanted.approveLimit() != null && wanted.approveLimit().signum() < 0) {
            throw fieldError("modules", "The approval limit cannot be negative.");
        }
        UserPermissions clean = new UserPermissions(
                modules,
                wanted.bulk() && (modules.contains("sell") || modules.contains("buy")),
                wanted.guardrails(),
                modules.contains("buy") ? wanted.approveLimit() : defaults.approveLimit());

        String over = overreach(actor, clean);
        if (over != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed", over, Map.of("field", "modules"));
        }
        return same(clean, defaults) ? null : clean;
    }

    /** What an Admin is trying to grant beyond their own access, or null. */
    private static String overreach(Actor actor, UserPermissions p) {
        if (actor.level() == WorkspaceRole.SUPER_ADMIN) {
            return null;
        }
        UserPermissions mine = actor.permissions();
        List<String> extra = p.modules().stream().filter(m -> !mine.modules().contains(m)).toList();
        if (!extra.isEmpty()) {
            return "You cannot grant access you do not have: "
                    + extra.stream().map(MODULE_LABEL::get).collect(Collectors.joining(", ")) + ".";
        }
        if (p.bulk() && !mine.bulk()) {
            return "You cannot grant bulk actions, because you do not have them.";
        }
        if (p.guardrails() && !mine.guardrails()) {
            return "You cannot grant guardrail changes, because you do not have them.";
        }
        BigDecimal limit = mine.approveLimit();
        if (p.modules().contains("buy") && limit != null
                && (p.approveLimit() == null || p.approveLimit().compareTo(limit) > 0)) {
            return "You can approve up to $%sk, so you cannot grant more.".formatted(limit.movePointLeft(3).stripTrailingZeros().toPlainString());
        }
        return null;
    }

    private static boolean same(UserPermissions a, UserPermissions b) {
        return a.bulk() == b.bulk()
                && a.guardrails() == b.guardrails()
                && Objects.equals(a.approveLimit() == null ? null : a.approveLimit().stripTrailingZeros(),
                        b.approveLimit() == null ? null : b.approveLimit().stripTrailingZeros())
                && a.modules().size() == b.modules().size()
                && a.modules().containsAll(b.modules());
    }

    UserPermissions effective(UUID tenantId, UserAccount user) {
        return user.getPermissions() != null
                ? user.getPermissions()
                : UserPermissions.of(policy.personaFor(tenantId, user.getSeatRole().wireValue()));
    }

    // -- Helpers ----------------------------------------------------------------------------------

    private void sendInvitation(UserAccount user, UserAccount inviter, Instant now) {
        invitations.findByUserId(user.getId()).forEach(i -> i.revoke(now));
        TokenService.OpaqueToken token = tokenService.issueOpaqueToken(INVITE_TTL);
        invitations.save(new UserInvitationEntity(user.getTenantId(), user.getId(), token.hash(), inviter.getId(), token.expiresAt()));
        try {
            mailer.sendInvitation(user.getEmail(), user.getFullName(), inviter.getFullName(),
                    tenants.get(user.getTenantId()).name(), token.value(), token.expiresAt());
        } catch (RuntimeException ex) {
            // Rolls the invitation back with it: nobody is listed as invited who was never told.
            log.error("Could not send the invitation mail for user {}", user.getId(), ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_failed",
                    "The invitation email could not be sent right now. Try again in a moment.");
        }
    }

    private UserInvitationEntity liveInvitation(String token) {
        Instant now = clock.now();
        return invitations.findByTokenHash(TokenService.sha256(token.strip()))
                .filter(i -> i.isLive(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_invitation",
                        "This invitation link has expired or was already used. Ask your admin to send a new one."));
    }

    private UserAccount invitedUser(UserInvitationEntity invitation) {
        return users.findById(invitation.getUserId())
                .filter(u -> u.getStatus() == UserStatus.INVITED)
                .orElseThrow(() -> ApiException.badRequest("invalid_invitation",
                        "This invitation has been withdrawn. Ask your admin to send a new one."));
    }

    private void record(Actor actor, String action, String target, String detail) {
        audit.save(new UserAuditEntity(actor.user().getTenantId(), actor.user().getId(), actor.user().getFullName(),
                action, target, detail));
    }

    private Map<UUID, String> names(UUID tenantId) {
        return users.findByTenantIdAndStatusNot(tenantId, UserStatus.DISABLED).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName, (a, b) -> a));
    }

    private static UserRecord record(UserAccount u, Map<UUID, String> names) {
        return new UserRecord(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getSeatRole().wireValue(),
                u.getWorkspaceRole().wireValue(),
                u.getPermissions(),
                switch (u.getStatus()) {
                    case INVITED -> "invited";
                    case SUSPENDED, DISABLED -> "suspended";
                    case ACTIVE -> "active";
                },
                u.getCreatedAt(),
                u.getInvitedAt(),
                u.getInvitedBy() == null ? null : names.getOrDefault(u.getInvitedBy(), "A former admin"),
                u.getLastLoginAt());
    }

    private String describe(UUID tenantId, UserAccount u) {
        return "%s · %s · %s".formatted(u.getWorkspaceRole().label(), u.getTitle(), summary(effective(tenantId, u)));
    }

    /** "Sell, Buy · bulk · approves to $50k", as the frontend writes it. */
    private static String summary(UserPermissions p) {
        List<String> parts = new ArrayList<>();
        parts.add(p.modules().size() == UserPermissions.MODULE_ORDER.size()
                ? "All modules"
                : p.modules().stream().map(MODULE_LABEL::get).collect(Collectors.joining(", ")));
        if (p.bulk()) {
            parts.add("bulk");
        }
        if (p.guardrails()) {
            parts.add("guardrails");
        }
        if (p.modules().contains("buy")) {
            BigDecimal l = p.approveLimit();
            parts.add(l == null ? "approves without limit"
                    : l.signum() == 0 ? "every order needs approval"
                    : "approves to $" + l.movePointLeft(3).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "k");
        }
        return String.join(" · ", parts);
    }

    private static String cleanName(String raw) {
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            throw fieldError("name", "That name is too short.");
        }
        return name;
    }

    private static SeatRole seat(String wire) {
        try {
            return SeatRole.from(wire);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("validation_failed", "Choose a job function.");
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message, Map.of("field", field));
    }
}

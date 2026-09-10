package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.identity.SeatRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A person with a seat.
 *
 * <p>Named {@code UserAccount} rather than {@code User} because {@code User} is already
 * taken by Spring Security in every file that would import both, and an alias at each
 * import site is worse than a clear name here.
 *
 * <p>The class holds no password logic. It stores a hash it is handed and can tell you
 * whether the account is currently usable; comparing a candidate password and deciding
 * what a failure costs belong to the service that owns the login transaction. What it
 * does own is the lockout arithmetic, because that is state on this row.
 */
@Entity
@Table(name = "users")
public class UserAccount extends TenantScopedEntity {

    /** Failed sign-ins before the account locks. Ten: generous for a person, useless for a script. */
    static final int MAX_FAILED_LOGINS = 10;

    /** How long a lock lasts. Short, because the point is to slow guessing, not to punish. */
    static final Duration LOCK_FOR = Duration.ofMinutes(15);

    @Column(name = "email", nullable = false)
    private String email;

    /** {@code lower(strip(email))}. Carries the unique index; see {@link #normalise}. */
    @Column(name = "email_normalised", nullable = false)
    private String emailNormalised;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Denormalised from the persona at signup so a later persona rename cannot rewrite it. */
    @Column(name = "title", nullable = false)
    private String title;

    @Convert(converter = SeatRole.JpaConverter.class)
    @Column(name = "seat_role", nullable = false)
    private SeatRole seatRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected UserAccount() {
        // JPA
    }

    UserAccount(UUID tenantId, String email, String passwordHash, String fullName, SeatRole seatRole, String title) {
        // Set explicitly rather than left to the @PrePersist stamp: signup runs before any
        // tenant is bound to the thread, because the tenant is being created by the same
        // transaction that creates this row.
        setTenantId(tenantId);
        this.email = email;
        this.emailNormalised = normalise(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.seatRole = seatRole;
        this.title = title;
        this.status = UserStatus.ACTIVE;
        this.emailVerified = false;
        this.failedLoginCount = 0;
    }

    /**
     * The form an address is compared and indexed by.
     *
     * <p>Case and surrounding whitespace only. The local part of an address is
     * case-sensitive per RFC 5321 and no dots are stripped, because deciding that
     * {@code a.b@gmail.com} and {@code ab@gmail.com} are the same person is a
     * provider-specific rule this application has no business encoding.
     */
    public static String normalise(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Whether this account may sign in right now. */
    boolean isUsable(Instant now) {
        return status == UserStatus.ACTIVE && !isLocked(now);
    }

    boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** A correct password: the counter resets and the lock, if any, clears. */
    void recordSuccessfulLogin(Instant now) {
        this.lastLoginAt = now;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /**
     * A wrong password. Locks the account once {@link #MAX_FAILED_LOGINS} is reached and
     * resets the counter, so the next window starts clean when the lock lifts.
     *
     * @return true if this failure locked the account
     */
    boolean recordFailedLogin(Instant now) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= MAX_FAILED_LOGINS) {
            this.lockedUntil = now.plus(LOCK_FOR);
            this.failedLoginCount = 0;
            return true;
        }
        return false;
    }

    /** A reset: the new hash takes effect and any lock is lifted, since the person proved control of the mailbox. */
    void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailNormalised() {
        return emailNormalised;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getTitle() {
        return title;
    }

    public SeatRole getSeatRole() {
        return seatRole;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}

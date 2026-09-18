package com.aatlas.identity.internal;

import com.aatlas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One signup verification: a code mailed to an address, and whether it came back.
 *
 * <p>Not tenant-scoped - it exists before any tenant does. The lifecycle is one way:
 * issued, then verified by the right code, then consumed by the signup it unlocked.
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerificationEntity extends BaseEntity {

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Column(name = "email_normalised", nullable = false, updatable = false)
    private String emailNormalised;

    @Column(name = "code_hash", nullable = false)
    private byte[] codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts_left", nullable = false)
    private int attemptsLeft;

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    @Column(name = "resend_available_at", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    protected EmailVerificationEntity() {
        // JPA
    }

    EmailVerificationEntity(String email, String emailNormalised, String clientIp) {
        this.email = email;
        this.emailNormalised = emailNormalised;
        this.clientIp = clientIp;
    }

    /** A fresh code: new digest, new expiry, attempts reset. The previous code stops working. */
    void issue(byte[] codeHash, Instant expiresAt, int attempts, Instant resendAvailableAt) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attemptsLeft = attempts;
        this.resendAvailableAt = resendAvailableAt;
    }

    void recordResend() {
        this.resendCount++;
    }

    void recordWrongCode() {
        this.attemptsLeft = Math.max(0, attemptsLeft - 1);
    }

    void markVerified(Instant now) {
        this.verifiedAt = now;
    }

    void markConsumed(Instant now) {
        this.consumedAt = now;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public String getEmail() {
        return email;
    }

    public String getEmailNormalised() {
        return emailNormalised;
    }

    public byte[] getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttemptsLeft() {
        return attemptsLeft;
    }

    public int getResendCount() {
        return resendCount;
    }

    public Instant getResendAvailableAt() {
        return resendAvailableAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}

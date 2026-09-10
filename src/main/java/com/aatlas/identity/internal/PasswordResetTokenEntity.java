package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One password-reset token: single use, one hour, only its digest stored.
 *
 * <p>Same reasoning as {@link RefreshTokenEntity}: the token travels once, by mail, and a
 * database dump must not be enough to take over an account.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetTokenEntity extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    protected PasswordResetTokenEntity() {
        // JPA
    }

    PasswordResetTokenEntity(UUID tenantId, UUID userId, byte[] tokenHash, Instant expiresAt, String clientIp) {
        setTenantId(tenantId);
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
    }

    boolean isLive(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    void markUsed(Instant now) {
        this.usedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}

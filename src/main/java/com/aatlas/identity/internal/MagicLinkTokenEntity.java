package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One emailed sign-in link: the digest of the token, who it signs in, and when it stops
 * working. The same shape as {@link PasswordResetTokenEntity}, kept apart because the two
 * have different lifetimes and a reset token must never sign anyone in.
 */
@Entity
@Table(name = "magic_link_tokens")
public class MagicLinkTokenEntity extends TenantScopedEntity {

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

    protected MagicLinkTokenEntity() {
        // JPA
    }

    MagicLinkTokenEntity(UUID tenantId, UUID userId, byte[] tokenHash, Instant expiresAt, String clientIp) {
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

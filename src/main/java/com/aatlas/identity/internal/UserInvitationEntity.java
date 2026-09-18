package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An emailed invitation link: seven days, single use, only its digest stored. */
@Entity
@Table(name = "user_invitations")
public class UserInvitationEntity extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "invited_by", updatable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserInvitationEntity() {
        // JPA
    }

    UserInvitationEntity(UUID tenantId, UUID userId, byte[] tokenHash, UUID invitedBy, Instant expiresAt) {
        setTenantId(tenantId);
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    boolean isLive(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    void markAccepted(Instant now) {
        this.acceptedAt = now;
    }

    void revoke(Instant now) {
        if (acceptedAt == null && revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

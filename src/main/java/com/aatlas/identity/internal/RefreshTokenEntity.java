package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token.
 *
 * <p>Only the SHA-256 of the token is stored. The token itself is returned to the caller
 * once and never written down, so a stolen database dump cannot be replayed against the
 * API - the same reasoning that keeps passwords out of the table.
 *
 * <p>Rows survive being spent. {@code usedAt} and {@code replacedBy} chain a family
 * together, which is what makes reuse detection possible: a token presented twice has
 * leaked, and the honest client and the thief are indistinguishable at that point, so the
 * whole family is revoked and both are made to sign in again.
 *
 * <p>{@code userId} is a plain column rather than a {@code @ManyToOne}. Refreshing needs
 * the id to mint a new access token and nothing else about the user, and an association
 * here would load an account on every rotation for no reason.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "client_ip")
    private String clientIp;

    protected RefreshTokenEntity() {
        // JPA
    }

    RefreshTokenEntity(
            UUID tenantId,
            UUID userId,
            byte[] tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            String userAgent,
            String clientIp) {
        setTenantId(tenantId);
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.clientIp = clientIp;
    }

    /** Live means issued, unspent, unrevoked and unexpired. Anything else cannot be exchanged. */
    boolean isLive(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    void markUsed(Instant now, UUID successor) {
        this.usedAt = now;
        this.replacedBy = successor;
    }

    void revoke(Instant now, String reason) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
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

    public Instant getRevokedAt() {
        return revokedAt;
    }
}

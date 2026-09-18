package com.aatlas.identity.internal;

import com.aatlas.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A provider identity the API has verified, redeemable once.
 *
 * <p>The browser holds the ticket; this row holds what it stands for. That split is the
 * point: signup and sign-in trust the row, never an email or subject the client sends.
 */
@Entity
@Table(name = "sso_tickets")
public class SsoTicketEntity extends BaseEntity {

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @Column(name = "email_verified", nullable = false, updatable = false)
    private boolean emailVerified;

    @Column(name = "full_name", updatable = false)
    private String fullName;

    @Column(name = "private_relay", nullable = false, updatable = false)
    private boolean privateRelay;

    @Column(name = "email_authoritative", nullable = false, updatable = false)
    private boolean emailAuthoritative;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    protected SsoTicketEntity() {
        // JPA
    }

    SsoTicketEntity(byte[] tokenHash, OidcVerifier.VerifiedIdentity identity, Instant expiresAt, String clientIp) {
        this.tokenHash = tokenHash;
        this.provider = identity.provider().wireValue();
        this.subject = identity.subject();
        this.email = identity.email();
        this.emailVerified = identity.emailVerified();
        this.fullName = identity.fullName();
        this.privateRelay = identity.privateRelay();
        this.emailAuthoritative = identity.emailAuthoritative();
        this.expiresAt = expiresAt;
        this.clientIp = clientIp;
    }

    boolean isLive(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    void markConsumed(Instant now) {
        this.consumedAt = now;
    }

    OidcVerifier.VerifiedIdentity identity() {
        return new OidcVerifier.VerifiedIdentity(
                SsoProvider.fromWire(provider), subject, email, emailVerified, fullName, privateRelay, emailAuthoritative);
    }
}

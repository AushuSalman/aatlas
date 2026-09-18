package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A Google or Apple account linked to a user, by the provider's stable subject. */
@Entity
@Table(name = "user_identities")
public class UserIdentityEntity extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    /** The address the provider reported when the link was made. Informational; never matched on. */
    @Column(name = "email", nullable = false)
    private String email;

    protected UserIdentityEntity() {
        // JPA
    }

    UserIdentityEntity(UUID tenantId, UUID userId, SsoProvider provider, String subject, String email) {
        setTenantId(tenantId);
        this.userId = userId;
        this.provider = provider.wireValue();
        this.subject = subject;
        this.email = email;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }
}

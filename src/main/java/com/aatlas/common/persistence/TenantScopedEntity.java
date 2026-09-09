package com.aatlas.common.persistence;

import com.aatlas.common.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;

/**
 * Base for every business table.
 *
 * <p>{@code tenant_id} is stamped on insert from the request's JWT, never accepted from
 * the caller. It is belt; PostgreSQL row-level security is braces. Both are needed:
 * the column makes the composite indexes work, RLS makes a forgotten {@code where}
 * clause harmless instead of a cross-tenant leak.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void stampTenant() {
        if (tenantId == null) {
            tenantId = TenantContext.requireTenantId();
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}

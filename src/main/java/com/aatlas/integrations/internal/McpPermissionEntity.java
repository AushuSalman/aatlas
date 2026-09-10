package com.aatlas.integrations.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A tenant's override of one catalogued MCP permission. Row absent = the catalogue's
 * {@code defaultOn}/{@code requiresApproval} applies.
 */
@Entity
@Table(name = "mcp_permission")
public class McpPermissionEntity extends TenantScopedEntity {

    @Column(name = "permission_key", nullable = false, updatable = false)
    private String permissionKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    protected McpPermissionEntity() {
        // JPA
    }

    McpPermissionEntity(UUID tenantId, String permissionKey, boolean enabled, boolean requiresApproval) {
        setTenantId(tenantId);
        this.permissionKey = permissionKey;
        this.enabled = enabled;
        this.requiresApproval = requiresApproval;
    }

    public String getPermissionKey() {
        return permissionKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }
}

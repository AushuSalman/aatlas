package com.aatlas.policy.internal;

import com.aatlas.common.persistence.BaseEntity;
import com.aatlas.policy.Persona;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code role_policy}: a seat's permissions, either the platform default
 * ({@code tenantId} null) or one tenant's override.
 *
 * <p>Extends {@link BaseEntity} rather than {@code TenantScopedEntity} because the
 * defaults have no tenant, and the stamping in {@code TenantScopedEntity} would give them
 * whichever tenant happened to be on the thread.
 */
@Entity
@Table(name = "role_policy")
public class RolePolicyEntity extends BaseEntity {

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "level", nullable = false)
    private String level;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "modules", nullable = false, columnDefinition = "text[]")
    private List<String> modules;

    @Column(name = "bulk", nullable = false)
    private boolean bulk;

    @Column(name = "guardrails", nullable = false)
    private boolean guardrails;

    @Column(name = "approve_limit", precision = 14, scale = 4)
    private BigDecimal approveLimit;

    @Column(name = "approver_role")
    private String approverRole;

    @Column(name = "blurb", nullable = false)
    private String blurb;

    protected RolePolicyEntity() {
        // JPA
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getRole() {
        return role;
    }

    public String getApproverRole() {
        return approverRole;
    }

    /**
     * The row as a persona. The approver's title is resolved by the caller, which holds
     * the whole set; a row only knows the approver's role key.
     */
    Persona toPersona(String approverTitle) {
        return new Persona(
                role,
                title,
                Persona.Side.from(side),
                Persona.Level.from(level),
                modules,
                bulk,
                guardrails,
                approveLimit == null ? null : approveLimit.stripTrailingZeros(),
                approverTitle,
                blurb);
    }
}

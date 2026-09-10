package com.aatlas.policy.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * One save or reset of a tenant's guardrails, with the values as saved.
 *
 * <p>The values are a {@code jsonb} snapshot: the history screen renders them verbatim and
 * nothing filters on one limit, which is the blueprint's rule for when a payload is JSON
 * rather than columns. Who and when are columns because the list sorts and pages on them.
 */
@Entity
@Table(name = "pricing_guardrail_history")
public class GuardrailHistoryEntity extends TenantScopedEntity {

    /** {@code set} for a save, {@code reset} for a return to the defaults. */
    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Type(JsonType.class)
    @Column(name = "snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private GuardrailValues snapshot;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_by_role", updatable = false)
    private String changedByRole;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected GuardrailHistoryEntity() {
        // JPA
    }

    GuardrailHistoryEntity(
            UUID tenantId, String action, GuardrailValues snapshot, UUID changedBy, String changedByRole,
            Instant changedAt) {
        setTenantId(tenantId);
        this.action = action;
        this.snapshot = snapshot;
        this.changedBy = changedBy;
        this.changedByRole = changedByRole;
        this.changedAt = changedAt;
    }

    public String getAction() {
        return action;
    }

    GuardrailValues getSnapshot() {
        return snapshot;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public String getChangedByRole() {
        return changedByRole;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}

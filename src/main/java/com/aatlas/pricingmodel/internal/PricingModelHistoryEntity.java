package com.aatlas.pricingmodel.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.history.PricingModel;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * One save or reset of a tenant's pricing-model overrides, with the override map as it
 * was stored.
 *
 * <p>The snapshot is a {@code jsonb} map: the history screen renders it verbatim and
 * nothing filters on one parameter, which is the blueprint's rule for when a payload is
 * JSON rather than columns. Who and when are columns because the list sorts and pages on
 * them.
 */
@Entity
@Table(name = "pricing_model_history")
public class PricingModelHistoryEntity extends TenantScopedEntity {

    /** {@code set} for a save, {@code reset} for a return to the defaults. */
    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Type(JsonType.class)
    @Column(name = "snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, PricingModel.Setting> snapshot;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_by_role", updatable = false)
    private String changedByRole;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected PricingModelHistoryEntity() {
        // JPA
    }

    PricingModelHistoryEntity(UUID tenantId, String action, Map<String, PricingModel.Setting> snapshot,
            UUID changedBy, String changedByRole, Instant changedAt) {
        setTenantId(tenantId);
        this.action = action;
        this.snapshot = snapshot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshot);
        this.changedBy = changedBy;
        this.changedByRole = changedByRole;
        this.changedAt = changedAt;
    }

    public String getAction() {
        return action;
    }

    Map<String, PricingModel.Setting> getSnapshot() {
        return snapshot == null ? Map.of() : snapshot;
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

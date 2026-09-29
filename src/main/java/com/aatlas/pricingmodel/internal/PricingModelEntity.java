package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A tenant's saved pricing-model overrides. Keyed by the tenant, because there is exactly
 * one row per tenant and the row's identity <em>is</em> the tenant.
 *
 * <p>{@code settings} is the override map exactly as {@link PricingModel.Config#overrides()}
 * gives it: only what differs from the registry defaults, keyed by parameter key. Absent
 * for a tenant that has never saved, and {@code {}} after a reset; both mean the defaults.
 * {@code version} is a wrapper so Spring Data can tell a new row (null) from a loaded one
 * and persist rather than merge.
 */
@Entity
@Table(name = "pricing_model_settings")
@EntityListeners(AuditingEntityListener.class)
public class PricingModelEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Type(JsonType.class)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, PricingModel.Setting> settings;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PricingModelEntity() {
        // JPA
    }

    PricingModelEntity(UUID tenantId, Map<String, PricingModel.Setting> settings, UUID updatedBy) {
        this.tenantId = tenantId;
        apply(settings, updatedBy);
    }

    void apply(Map<String, PricingModel.Setting> settings, UUID updatedBy) {
        this.settings = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
        this.updatedBy = updatedBy;
    }

    /** The stored overrides; never null. Normalise through {@link PricingModel.Config#of} before reading. */
    Map<String, PricingModel.Setting> getSettings() {
        return settings == null ? Map.of() : settings;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

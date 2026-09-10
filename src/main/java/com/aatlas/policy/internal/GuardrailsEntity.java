package com.aatlas.policy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A tenant's saved guardrails. Keyed by the tenant, because there is exactly one row
 * per tenant and the row's identity <em>is</em> the tenant.
 *
 * <p>Absent for a tenant that has never saved: {@code GuardrailsService} answers with the
 * defaults then. {@code version} is a wrapper so Spring Data can tell a new row (null)
 * from a loaded one and persist rather than merge.
 */
@Entity
@Table(name = "pricing_guardrails")
@EntityListeners(AuditingEntityListener.class)
public class GuardrailsEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "min_margin_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal minMarginPct;

    @Column(name = "max_discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDiscountPct;

    @Column(name = "max_speed_premium_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxSpeedPremiumPct;

    @Column(name = "max_market_deviation_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxMarketDeviationPct;

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

    protected GuardrailsEntity() {
        // JPA
    }

    GuardrailsEntity(UUID tenantId, GuardrailValues values, UUID updatedBy) {
        this.tenantId = tenantId;
        apply(values, updatedBy);
    }

    void apply(GuardrailValues values, UUID updatedBy) {
        this.minMarginPct = values.minMarginPct();
        this.maxDiscountPct = values.maxDiscountPct();
        this.maxSpeedPremiumPct = values.maxSpeedPremiumPct();
        this.maxMarketDeviationPct = values.maxMarketDeviationPct();
        this.updatedBy = updatedBy;
    }

    GuardrailValues values() {
        return new GuardrailValues(minMarginPct, maxDiscountPct, maxSpeedPremiumPct, maxMarketDeviationPct)
                .normalised();
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

package com.aatlas.suppliers.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Fulfilment risk, 0-100, with the factors the screen lists verbatim. Mirrors
 * {@code supplierRisk} in the frontend's {@code intel/buy2.ts}.
 *
 * <p>Primary key is the supplier's own id; see {@link SupplierTermsEntity} for why.
 */
@Entity
@Table(name = "supplier_risk")
@EntityListeners(AuditingEntityListener.class)
class SupplierRiskEntity {

    @Id
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "score")
    private Integer score;

    @Column(name = "level")
    private String level;

    @Column(name = "capacity")
    private String capacity;

    @Column(name = "lead_variance_days")
    private Double leadVarianceDays;

    @Column(name = "consistency")
    private String consistency;

    @Column(name = "trend")
    private String trend;

    @Column(name = "recent_delay_pct")
    private Double recentDelayPct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors", columnDefinition = "jsonb", nullable = false)
    private List<RiskFactor> factors;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SupplierRiskEntity() {
        // JPA
    }

    SupplierRiskEntity(UUID supplierId, UUID tenantId, SupplierRisk risk) {
        this.supplierId = supplierId;
        this.tenantId = tenantId;
        apply(risk);
    }

    void apply(SupplierRisk risk) {
        this.score = risk.score();
        this.level = risk.level();
        this.capacity = risk.capacity();
        this.leadVarianceDays = risk.leadVarianceDays();
        this.consistency = risk.consistency();
        this.trend = risk.trend();
        this.recentDelayPct = risk.recentDelayPct();
        this.factors = risk.factors();
        this.computedAt = risk.computedAt();
    }

    UUID getSupplierId() {
        return supplierId;
    }
}

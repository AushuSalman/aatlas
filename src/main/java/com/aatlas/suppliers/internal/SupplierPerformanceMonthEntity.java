package com.aatlas.suppliers.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One month of a supplier's on-time record. The profile reads the last six. */
@Entity
@Table(name = "supplier_performance_months")
class SupplierPerformanceMonthEntity extends TenantScopedEntity {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "month", nullable = false)
    private LocalDate month;

    @Column(name = "otif_pct", nullable = false)
    private double otifPct;

    @Column(name = "defect_pct")
    private Double defectPct;

    @Column(name = "orders")
    private Integer orders;

    @Column(name = "spend")
    private BigDecimal spend;

    @Column(name = "avg_lead_days")
    private Double avgLeadDays;

    protected SupplierPerformanceMonthEntity() {
        // JPA
    }

    SupplierPerformanceMonthEntity(UUID supplierId, LocalDate month, double otifPct) {
        this.supplierId = supplierId;
        this.month = month;
        this.otifPct = otifPct;
    }

    UUID getSupplierId() {
        return supplierId;
    }

    LocalDate getMonth() {
        return month;
    }

    double getOtifPct() {
        return otifPct;
    }
}

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
 * What a supplier's paperwork says: {@code CommercialTerms} from the frontend's
 * {@code intel/terms.ts}, plus the certifications shown on the profile and the four
 * order-mechanics columns the side-by-side compare reads.
 *
 * <p>Primary key is the supplier's own id, not a fresh uuid: there is exactly one row per
 * supplier by definition. {@link com.aatlas.common.persistence.UuidV7Generator} honours an
 * id assigned explicitly, so the constructor sets it to {@code supplier.getId()} directly
 * rather than relying on {@code @GeneratedValue}.
 */
@Entity
@Table(name = "supplier_terms")
@EntityListeners(AuditingEntityListener.class)
class SupplierTermsEntity {

    @Id
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "credit_days", nullable = false)
    private int creditDays;

    @Column(name = "terms_label", nullable = false)
    private String termsLabel;

    @Column(name = "early_pay_discount_pct", nullable = false)
    private double earlyPayDiscountPct;

    @Column(name = "early_pay_days", nullable = false)
    private int earlyPayDays;

    @Column(name = "late_penalty_pct_per_week", nullable = false)
    private double latePenaltyPctPerWeek;

    @Column(name = "late_penalty_cap_pct", nullable = false)
    private double latePenaltyCapPct;

    @Column(name = "warranty_months", nullable = false)
    private int warrantyMonths;

    @Column(name = "quote_validity_days", nullable = false)
    private int quoteValidityDays;

    @Column(name = "incoterm", nullable = false)
    private String incoterm;

    @Column(name = "invoice_accuracy_pct", nullable = false)
    private double invoiceAccuracyPct;

    @Column(name = "capacity_units_month", nullable = false)
    private int capacityUnitsMonth;

    @Column(name = "moq", nullable = false)
    private int moq;

    @Column(name = "order_multiple", nullable = false)
    private int orderMultiple;

    @Column(name = "quality_ppm", nullable = false)
    private int qualityPpm;

    @Column(name = "response_hours", nullable = false)
    private int responseHours;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "certifications", nullable = false)
    private List<String> certifications;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SupplierTermsEntity() {
        // JPA
    }

    SupplierTermsEntity(UUID supplierId, UUID tenantId, CommercialTerms t, int moq, int orderMultiple,
            int qualityPpm, int responseHours, List<String> certifications) {
        this.supplierId = supplierId;
        this.tenantId = tenantId;
        apply(t);
        this.moq = moq;
        this.orderMultiple = orderMultiple;
        this.qualityPpm = qualityPpm;
        this.responseHours = responseHours;
        this.certifications = certifications;
    }

    /** Overwrites every {@code CommercialTerms} column; the order-mechanics columns are untouched. */
    void apply(CommercialTerms t) {
        this.creditDays = t.creditDays();
        this.termsLabel = t.termsLabel();
        this.earlyPayDiscountPct = t.earlyPayDiscountPct();
        this.earlyPayDays = t.earlyPayDays();
        this.latePenaltyPctPerWeek = t.latePenaltyPctPerWeek();
        this.latePenaltyCapPct = t.latePenaltyCapPct();
        this.warrantyMonths = t.warrantyMonths();
        this.quoteValidityDays = t.quoteValidityDays();
        this.incoterm = t.incoterm();
        this.invoiceAccuracyPct = t.invoiceAccuracyPct();
        this.capacityUnitsMonth = t.capacityUnitsMonth();
    }

    CommercialTerms toCommercialTerms() {
        return new CommercialTerms(creditDays, termsLabel, earlyPayDiscountPct, earlyPayDays,
                latePenaltyPctPerWeek, latePenaltyCapPct, warrantyMonths, quoteValidityDays, incoterm,
                invoiceAccuracyPct, capacityUnitsMonth);
    }

    UUID getSupplierId() {
        return supplierId;
    }

    List<String> getCertifications() {
        return certifications;
    }

    void setCertifications(List<String> certifications) {
        this.certifications = certifications;
    }
}

package com.aatlas.rfq.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** See V19: {@code rfq_quote}. One row per invite that has answered - declined or quoted. */
@Entity
@Table(name = "rfq_quote")
class RfqQuoteEntity extends TenantScopedEntity {

    @Column(name = "rfq_id", nullable = false, updatable = false)
    private UUID rfqId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private String supplierId;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "declined", nullable = false, updatable = false)
    private boolean declined;

    @Column(name = "quoted_unit")
    private BigDecimal quotedUnit;

    @Column(name = "quoted_landed")
    private BigDecimal quotedLanded;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "lead_days")
    private Integer leadDays;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "payment_terms")
    private String paymentTerms;

    @Column(name = "note")
    private String note;

    @Column(name = "vs_expected_pct")
    private BigDecimal vsExpectedPct;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "entered_by")
    private UUID enteredBy;

    /** True only for a demo reply {@code RfqEngine.simulate} generated. See V24. */
    @Column(name = "simulated", nullable = false)
    private boolean simulated;

    protected RfqQuoteEntity() {
        // JPA
    }

    RfqQuoteEntity(UUID rfqId, String supplierId, String name, boolean declined, BigDecimal quotedUnit,
            BigDecimal quotedLanded, String currency, Integer leadDays, LocalDate validUntil, String paymentTerms,
            String note, BigDecimal vsExpectedPct, Instant receivedAt, UUID enteredBy, boolean simulated) {
        this.rfqId = rfqId;
        this.supplierId = supplierId;
        this.name = name;
        this.declined = declined;
        this.quotedUnit = quotedUnit;
        this.quotedLanded = quotedLanded;
        this.currency = currency;
        this.leadDays = leadDays;
        this.validUntil = validUntil;
        this.paymentTerms = paymentTerms;
        this.note = note;
        this.vsExpectedPct = vsExpectedPct;
        this.receivedAt = receivedAt;
        this.enteredBy = enteredBy;
        this.simulated = simulated;
    }

    UUID getRfqId() {
        return rfqId;
    }

    String getSupplierId() {
        return supplierId;
    }

    String getName() {
        return name;
    }

    boolean isDeclined() {
        return declined;
    }

    BigDecimal getQuotedUnit() {
        return quotedUnit;
    }

    BigDecimal getQuotedLanded() {
        return quotedLanded;
    }

    String getCurrency() {
        return currency;
    }

    Integer getLeadDays() {
        return leadDays;
    }

    LocalDate getValidUntil() {
        return validUntil;
    }

    String getPaymentTerms() {
        return paymentTerms;
    }

    String getNote() {
        return note;
    }

    BigDecimal getVsExpectedPct() {
        return vsExpectedPct;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }

    UUID getEnteredBy() {
        return enteredBy;
    }

    boolean isSimulated() {
        return simulated;
    }
}

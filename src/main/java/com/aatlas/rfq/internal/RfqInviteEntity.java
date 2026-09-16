package com.aatlas.rfq.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** See V19: {@code rfq_invite}. */
@Entity
@Table(name = "rfq_invite")
class RfqInviteEntity extends TenantScopedEntity {

    public enum Status { invited, quoted, declined }

    @Column(name = "rfq_id", nullable = false, updatable = false)
    private UUID rfqId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private String supplierId;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "country", nullable = false, updatable = false)
    private String country;

    @Column(name = "route", nullable = false, updatable = false)
    private String route;

    @Column(name = "expected_landed", nullable = false, updatable = false)
    private BigDecimal expectedLanded;

    @Column(name = "lead_days", nullable = false, updatable = false)
    private int leadDays;

    @Column(name = "on_time_pct", nullable = false, updatable = false)
    private BigDecimal onTimePct;

    @Column(name = "risk_level", nullable = false, updatable = false)
    private String riskLevel;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    protected RfqInviteEntity() {
        // JPA
    }

    RfqInviteEntity(UUID rfqId, String supplierId, String name, String country, String route,
            BigDecimal expectedLanded, int leadDays, BigDecimal onTimePct, String riskLevel) {
        this.rfqId = rfqId;
        this.supplierId = supplierId;
        this.name = name;
        this.country = country;
        this.route = route;
        this.expectedLanded = expectedLanded;
        this.leadDays = leadDays;
        this.onTimePct = onTimePct;
        this.riskLevel = riskLevel;
        this.status = Status.invited;
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

    String getCountry() {
        return country;
    }

    String getRoute() {
        return route;
    }

    BigDecimal getExpectedLanded() {
        return expectedLanded;
    }

    int getLeadDays() {
        return leadDays;
    }

    BigDecimal getOnTimePct() {
        return onTimePct;
    }

    String getRiskLevel() {
        return riskLevel;
    }

    Instant getSentAt() {
        return sentAt;
    }

    void markSent(Instant at) {
        this.sentAt = at;
    }

    Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }
}

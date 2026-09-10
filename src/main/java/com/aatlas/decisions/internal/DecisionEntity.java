package com.aatlas.decisions.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.decisions.DecisionKind;
import com.aatlas.decisions.DecisionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** See V12: {@code decision}. */
@Entity
@Table(name = "decision")
public class DecisionEntity extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "item_number")
    private String itemNumber;

    @Column(name = "scope", nullable = false)
    private String scope;

    @Column(name = "recommended", nullable = false)
    private BigDecimal recommended;

    @Column(name = "applied", nullable = false)
    private BigDecimal applied;

    @Column(name = "expected_impact", nullable = false)
    private BigDecimal expectedImpact;

    @Column(name = "impact_label", nullable = false)
    private String impactLabel;

    @Column(name = "detail", nullable = false)
    private String detail;

    @Column(name = "count")
    private Integer count;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    /** Mirrors {@link DecisionKind}'s wire values; JPA enums are cleanest kept separate from the public API's. */
    public enum Kind { sell, buy, bulk_sell, bulk_buy }

    public enum Status { applied, pending, approved, rejected }

    protected DecisionEntity() {
        // JPA
    }

    public DecisionEntity(UUID userId, DecisionKind kind, String title, String itemNumber, String scope,
            BigDecimal recommended, BigDecimal applied, BigDecimal expectedImpact, String impactLabel,
            String detail, Integer count, DecisionStatus status) {
        this.userId = userId;
        this.kind = toEntityKind(kind);
        this.title = title;
        this.itemNumber = itemNumber;
        this.scope = scope;
        this.recommended = recommended;
        this.applied = applied;
        this.expectedImpact = expectedImpact;
        this.impactLabel = impactLabel;
        this.detail = detail;
        this.count = count;
        this.status = Status.valueOf(status.wire().replace('-', '_'));
    }

    private static Kind toEntityKind(DecisionKind kind) {
        return Kind.valueOf(kind.wire().replace('-', '_'));
    }

    public UUID getUserId() {
        return userId;
    }

    public Kind getKind() {
        return kind;
    }

    public DecisionKind kindPublic() {
        return DecisionKind.fromWire(kind.name().replace('_', '-'));
    }

    public String getTitle() {
        return title;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public String getScope() {
        return scope;
    }

    public BigDecimal getRecommended() {
        return recommended;
    }

    public BigDecimal getApplied() {
        return applied;
    }

    public BigDecimal getExpectedImpact() {
        return expectedImpact;
    }

    public String getImpactLabel() {
        return impactLabel;
    }

    public String getDetail() {
        return detail;
    }

    public Integer getCount() {
        return count;
    }

    public Status getStatus() {
        return status;
    }

    public DecisionStatus statusPublic() {
        return DecisionStatus.fromWire(status.name());
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}

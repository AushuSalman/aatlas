package com.aatlas.decisions.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.decisions.QuoteBreakdown;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** See V12: {@code quote}. One per decision, sell-side only. */
@Entity
@Table(name = "quote")
public class QuoteEntity extends TenantScopedEntity {

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "volume_break_pct", nullable = false)
    private BigDecimal volumeBreakPct;

    @Column(name = "next_break_at")
    private Integer nextBreakAt;

    @Column(name = "next_break_pct")
    private BigDecimal nextBreakPct;

    @Column(name = "customer_discount_pct", nullable = false)
    private BigDecimal customerDiscountPct;

    @Column(name = "book_optimal", nullable = false)
    private BigDecimal bookOptimal;

    @Column(name = "book_aggressive", nullable = false)
    private BigDecimal bookAggressive;

    @Column(name = "optimal", nullable = false)
    private BigDecimal optimal;

    @Column(name = "aggressive", nullable = false)
    private BigDecimal aggressive;

    @Column(name = "recommended", nullable = false)
    private BigDecimal recommended;

    @Column(name = "recommended_tier", nullable = false)
    private String recommendedTier;

    @Column(name = "clamped_by_floor", nullable = false)
    private boolean clampedByFloor;

    @Column(name = "effective_discount_pct", nullable = false)
    private BigDecimal effectiveDiscountPct;

    protected QuoteEntity() {
        // JPA
    }

    public QuoteEntity(UUID decisionId, QuoteBreakdown q) {
        this.decisionId = decisionId;
        this.qty = q.qty();
        this.volumeBreakPct = q.volumeBreakPct();
        this.nextBreakAt = q.nextBreakAt();
        this.nextBreakPct = q.nextBreakPct();
        this.customerDiscountPct = q.customerDiscountPct();
        this.bookOptimal = q.bookOptimal();
        this.bookAggressive = q.bookAggressive();
        this.optimal = q.optimal();
        this.aggressive = q.aggressive();
        this.recommended = q.recommended();
        this.recommendedTier = q.recommendedTier();
        this.clampedByFloor = q.clampedByFloor();
        this.effectiveDiscountPct = q.effectiveDiscountPct();
    }

    public QuoteBreakdown toBreakdown() {
        return new QuoteBreakdown(qty, volumeBreakPct, nextBreakAt, nextBreakPct, customerDiscountPct,
                bookOptimal, bookAggressive, optimal, aggressive, recommended, recommendedTier,
                clampedByFloor, effectiveDiscountPct);
    }

    public UUID getDecisionId() {
        return decisionId;
    }
}

package com.aatlas.decisions.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** See V12: {@code deal}. Seeded (recorded=false) or written live (recorded=true). */
@Entity
@Table(name = "deal")
public class DealEntity extends TenantScopedEntity {

    @Column(name = "deal_key", nullable = false, updatable = false)
    private String dealKey;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "side", nullable = false)
    private String side;

    @Column(name = "item_number", nullable = false)
    private String itemNumber;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "counterparty", nullable = false)
    private String counterparty;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "cost", nullable = false)
    private BigDecimal cost;

    @Column(name = "baseline_price", nullable = false)
    private BigDecimal baselinePrice;

    @Column(name = "suggested_price", nullable = false)
    private BigDecimal suggestedPrice;

    @Column(name = "actual_price", nullable = false)
    private BigDecimal actualPrice;

    @Column(name = "followed", nullable = false)
    private boolean followed;

    @Column(name = "gain", nullable = false)
    private BigDecimal gain;

    @Column(name = "lost", nullable = false)
    private BigDecimal lost;

    @Column(name = "recorded", nullable = false)
    private boolean recorded;

    @Column(name = "customer")
    private String customer;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "below_floor")
    private Boolean belowFloor;

    @Column(name = "destination_id")
    private String destinationId;

    @Column(name = "deal_date", nullable = false)
    private LocalDate dealDate;

    protected DealEntity() {
        // JPA
    }

    public DealEntity(String dealKey, String side, String itemNumber, String description, String counterparty, int qty,
            BigDecimal cost, BigDecimal baselinePrice, BigDecimal suggestedPrice, BigDecimal actualPrice,
            boolean followed, BigDecimal gain, BigDecimal lost, boolean recorded, String customer,
            Instant recordedAt, Boolean belowFloor, String destinationId, LocalDate dealDate, UUID decisionId) {
        this.dealKey = dealKey;
        this.side = side;
        this.itemNumber = itemNumber;
        this.description = description;
        this.counterparty = counterparty;
        this.qty = qty;
        this.cost = cost;
        this.baselinePrice = baselinePrice;
        this.suggestedPrice = suggestedPrice;
        this.actualPrice = actualPrice;
        this.followed = followed;
        this.gain = gain;
        this.lost = lost;
        this.recorded = recorded;
        this.customer = customer;
        this.recordedAt = recordedAt;
        this.belowFloor = belowFloor;
        this.destinationId = destinationId;
        this.dealDate = dealDate;
        this.decisionId = decisionId;
    }

    public String getDealKey() {
        return dealKey;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public String getSide() {
        return side;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public String getDescription() {
        return description;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public BigDecimal getBaselinePrice() {
        return baselinePrice;
    }

    public BigDecimal getSuggestedPrice() {
        return suggestedPrice;
    }

    public BigDecimal getActualPrice() {
        return actualPrice;
    }

    public boolean isFollowed() {
        return followed;
    }

    public BigDecimal getGain() {
        return gain;
    }

    public BigDecimal getLost() {
        return lost;
    }

    public boolean isRecorded() {
        return recorded;
    }

    public String getCustomer() {
        return customer;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Boolean getBelowFloor() {
        return belowFloor;
    }

    public String getDestinationId() {
        return destinationId;
    }

    public LocalDate getDealDate() {
        return dealDate;
    }
}

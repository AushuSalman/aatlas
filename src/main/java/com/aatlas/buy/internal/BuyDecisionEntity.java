package com.aatlas.buy.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What {@code POST /buy/select} recorded: a decision and (when the seat could commit alone)
 * a purchase, or a request sent up for approval.
 *
 * <p>Stand-in for the {@code decisions} module's own table (Track D / History-Analytics, a
 * different worktree, not yet in this one) - see {@link DecisionRecorder}'s Javadoc.
 *
 * <p>TODO(merge): retarget at the real {@code decisions} writer once it exists.
 */
@Entity
@Table(name = "buy_decisions")
class BuyDecisionEntity extends TenantScopedEntity {

    static final String STATUS_RECORDED = "recorded";
    static final String STATUS_PENDING_APPROVAL = "pending_approval";

    @Column(name = "item_number", nullable = false)
    private String itemNumber;

    @Column(name = "region_key", nullable = false)
    private String regionKey;

    @Column(name = "destination_store_code", nullable = false)
    private String destinationStoreCode;

    @Column(name = "option_key", nullable = false)
    private String optionKey;

    @Column(name = "order_value", nullable = false)
    private BigDecimal orderValue;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "approver_role")
    private String approverRole;

    @Column(name = "approve_limit")
    private BigDecimal approveLimit;

    /** The supplier actually chosen (see {@code BuySelectRequest.supplierId}); null for pre-V24 rows. */
    @Column(name = "supplier_id")
    private String supplierId;

    /** The real {@code decisions.Decision} this row mirrors, so a pending award can be resolved later. */
    @Column(name = "decision_id")
    private UUID decisionId;

    /** The order quantity, so a granted approval can mirror the purchase with the real quantity. */
    @Column(name = "qty")
    private Integer qty;

    protected BuyDecisionEntity() {
        // JPA
    }

    BuyDecisionEntity(String itemNumber, String regionKey, String destinationStoreCode, String optionKey,
            String supplierId, Integer qty, BigDecimal orderValue, String status, UUID decidedBy, Instant decidedAt,
            String approverRole, BigDecimal approveLimit) {
        this.itemNumber = itemNumber;
        this.regionKey = regionKey;
        this.destinationStoreCode = destinationStoreCode;
        this.optionKey = optionKey;
        this.supplierId = supplierId;
        this.qty = qty;
        this.orderValue = orderValue;
        this.status = status;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.approverRole = approverRole;
        this.approveLimit = approveLimit;
    }

    String getItemNumber() {
        return itemNumber;
    }

    String getRegionKey() {
        return regionKey;
    }

    String getDestinationStoreCode() {
        return destinationStoreCode;
    }

    String getOptionKey() {
        return optionKey;
    }

    String getSupplierId() {
        return supplierId;
    }

    Integer getQty() {
        return qty;
    }

    BigDecimal getOrderValue() {
        return orderValue;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }
}

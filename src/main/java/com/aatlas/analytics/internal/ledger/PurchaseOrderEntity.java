package com.aatlas.analytics.internal.ledger;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the procurement ledger. See V12 and V22 for what each column means. */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrderEntity extends TenantScopedEntity {

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "po_number", nullable = false)
    private String poNumber;

    @Column(name = "po_ref")
    private String poRef;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "supplier_id", nullable = false)
    private String supplierId;

    @Column(name = "supplier_name", nullable = false)
    private String supplierName;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "item_number", nullable = false)
    private String itemNumber;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "branch_id")
    private String branchId;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "region_key")
    private String regionKey;

    @Column(name = "region_label")
    private String regionLabel;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "qty_received")
    private Integer qtyReceived;

    @Column(name = "ex_works", nullable = false)
    private BigDecimal exWorks;

    @Column(name = "freight", nullable = false)
    private BigDecimal freight;

    @Column(name = "duty", nullable = false)
    private BigDecimal duty;

    @Column(name = "landed", nullable = false)
    private BigDecimal landed;

    @Column(name = "baseline", nullable = false)
    private BigDecimal baseline;

    @Column(name = "target", nullable = false)
    private BigDecimal target;

    @Column(name = "followed", nullable = false)
    private boolean followed;

    @Column(name = "spend", nullable = false)
    private BigDecimal spend;

    @Column(name = "baseline_spend", nullable = false)
    private BigDecimal baselineSpend;

    @Column(name = "saved", nullable = false)
    private BigDecimal saved;

    @Column(name = "leaked", nullable = false)
    private BigDecimal leaked;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "promised_days")
    private Integer promisedDays;

    @Column(name = "actual_days")
    private Integer actualDays;

    @Column(name = "days_late")
    private Integer daysLate;

    @Column(name = "on_time")
    private Boolean onTime;

    @Column(name = "promised_date")
    private LocalDate promisedDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "source", nullable = false)
    private String source = "award";

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "source_line")
    private Integer sourceLine;

    @Column(name = "cost_basis", nullable = false)
    private String costBasis = "file";

    protected PurchaseOrderEntity() {
        // JPA
    }

    public PurchaseOrderEntity(PoRow row) {
        this.seq = row.seq();
        this.poNumber = row.id();
        this.poRef = row.poRef();
        this.orderDate = row.date();
        this.supplierId = row.supplierId();
        this.supplierName = row.supplierName();
        this.country = row.country();
        this.itemNumber = row.itemNumber();
        this.description = row.description();
        this.category = row.category();
        this.branchId = row.branchId();
        this.branchName = row.branchName();
        this.regionKey = row.regionKey();
        this.regionLabel = row.regionLabel();
        this.qty = row.qty();
        this.exWorks = BigDecimal.valueOf(row.exWorks());
        this.freight = BigDecimal.valueOf(row.freight());
        this.duty = BigDecimal.valueOf(row.duty());
        this.landed = BigDecimal.valueOf(row.landed());
        this.baseline = BigDecimal.valueOf(row.baseline());
        this.target = BigDecimal.valueOf(row.target());
        this.followed = row.followed();
        this.spend = BigDecimal.valueOf(row.spend());
        this.baselineSpend = BigDecimal.valueOf(row.baselineSpend());
        this.saved = BigDecimal.valueOf(row.saved());
        this.leaked = BigDecimal.valueOf(row.leaked());
        this.status = row.status();
        this.promisedDays = row.promisedDays();
        this.actualDays = row.actualDays();
        this.daysLate = row.daysLate();
        this.onTime = row.onTime();
        this.promisedDate = row.promisedDate();
        this.receivedDate = row.receivedDate();
        this.source = row.source() == null ? "award" : row.source();
    }

    /** Back to the pure-Java row the engine reduces. {@code seq} carries the tie-break order through. */
    public PoRow toRow() {
        return new PoRow(seq, poNumber, orderDate, supplierId, supplierName, country, itemNumber, description,
                category, branchId, branchName, regionKey, regionLabel, qty,
                exWorks.doubleValue(), freight.doubleValue(), duty.doubleValue(), landed.doubleValue(),
                baseline.doubleValue(), target.doubleValue(), followed,
                spend.doubleValue(), baselineSpend.doubleValue(), saved.doubleValue(), leaked.doubleValue(),
                status, promisedDays, actualDays, daysLate, onTime, promisedDate, receivedDate, poRef, source);
    }

    public String getPoNumber() {
        return poNumber;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public void setStoreId(UUID storeId) {
        this.storeId = storeId;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public String getBranchId() {
        return branchId;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getSource() {
        return source;
    }
}

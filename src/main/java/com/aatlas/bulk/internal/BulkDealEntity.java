package com.aatlas.bulk.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** One basket line an applied bulk strategy priced (sell) or awarded (buy). */
@Entity
@Table(name = "bulk_deal")
public class BulkDealEntity extends TenantScopedEntity {

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "item_number", nullable = false, updatable = false)
    private String itemNumber;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    @Column(name = "unit_price", updatable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "unit_cost", updatable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "supplier_key", updatable = false)
    private String supplierKey;

    @Column(name = "supplier_name", updatable = false)
    private String supplierName;

    protected BulkDealEntity() {
        // JPA
    }

    BulkDealEntity(UUID tenantId, UUID decisionId, String itemNumber, int qty, BigDecimal unitPrice,
            BigDecimal unitCost, String supplierKey, String supplierName) {
        setTenantId(tenantId);
        this.decisionId = decisionId;
        this.itemNumber = itemNumber;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.unitCost = unitCost;
        this.supplierKey = supplierKey;
        this.supplierName = supplierName;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public String getSupplierKey() {
        return supplierKey;
    }

    public String getSupplierName() {
        return supplierName;
    }
}

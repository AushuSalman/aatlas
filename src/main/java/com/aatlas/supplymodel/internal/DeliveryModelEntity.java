package com.aatlas.supplymodel.internal;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A tenant's trained delivery models: the serialised slip and late forests, the per-supplier
 * report and context, and what the run was fitted on. One row per tenant, keyed by the tenant.
 * A row with null models is a tenant the trainer visited and could not fit; its {@code note}
 * says why.
 */
@Entity
@Table(name = "delivery_models")
public class DeliveryModelEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** The slip model (days against the promise) as its protobuf bytes; null until a fit succeeded. */
    @Column(name = "slip_model")
    private byte[] slipModel;

    /** The late model (late as 0/1, read as a probability), the same way. */
    @Column(name = "late_model")
    private byte[] lateModel;

    @Type(JsonType.class)
    @Column(name = "suppliers", nullable = false, columnDefinition = "jsonb")
    private Map<String, SupplierRecord> suppliers = new LinkedHashMap<>();

    @Column(name = "trained_at")
    private Instant trainedAt;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "supplier_count", nullable = false)
    private int supplierCount;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "holdout_orders", nullable = false)
    private int holdoutOrders;

    @Column(name = "train_millis", nullable = false)
    private long trainMillis;

    @Column(name = "note")
    private String note;

    @Version
    @Column(name = "version")
    private Long version;

    protected DeliveryModelEntity() {
    }

    static DeliveryModelEntity fresh(UUID tenantId) {
        DeliveryModelEntity e = new DeliveryModelEntity();
        e.tenantId = tenantId;
        e.suppliers = new LinkedHashMap<>();
        return e;
    }

    /** A visit that could not fit: the models, if any, are dropped so stale ones never serve. */
    void untrained(String why, Instant at, int orders, LocalDate from, LocalDate to) {
        this.slipModel = null;
        this.lateModel = null;
        this.suppliers = new LinkedHashMap<>();
        this.trainedAt = at;
        this.orderCount = orders;
        this.supplierCount = 0;
        this.fromDate = from;
        this.toDate = to;
        this.holdoutOrders = 0;
        this.trainMillis = 0;
        this.note = why;
    }

    void trained(byte[] slipBytes, byte[] lateBytes, Map<String, SupplierRecord> suppliers, Instant at, int orders,
            LocalDate from, LocalDate to, int holdoutOrders, long millis, String note) {
        this.slipModel = slipBytes;
        this.lateModel = lateBytes;
        this.suppliers = new LinkedHashMap<>(suppliers);
        this.trainedAt = at;
        this.orderCount = orders;
        this.supplierCount = suppliers.size();
        this.fromDate = from;
        this.toDate = to;
        this.holdoutOrders = holdoutOrders;
        this.trainMillis = millis;
        this.note = note;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public byte[] getSlipModel() {
        return slipModel;
    }

    public byte[] getLateModel() {
        return lateModel;
    }

    public Map<String, SupplierRecord> getSuppliers() {
        return suppliers;
    }

    public Instant getTrainedAt() {
        return trainedAt;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public int getSupplierCount() {
        return supplierCount;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public int getHoldoutOrders() {
        return holdoutOrders;
    }

    public long getTrainMillis() {
        return trainMillis;
    }

    public String getNote() {
        return note;
    }

    public Long getVersion() {
        return version;
    }
}

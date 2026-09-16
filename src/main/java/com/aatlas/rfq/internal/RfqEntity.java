package com.aatlas.rfq.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.rfq.RfqStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** See V19: {@code rfq}. */
@Entity
@Table(name = "rfq")
class RfqEntity extends TenantScopedEntity {

    public enum Status { draft, sent, quoted, awarded, closed }

    @Column(name = "ref", nullable = false, updatable = false)
    private String ref;

    @Column(name = "item_number", nullable = false, updatable = false)
    private String itemNumber;

    @Column(name = "item_name", nullable = false, updatable = false)
    private String itemName;

    @Column(name = "region_key", nullable = false, updatable = false)
    private String regionKey;

    @Column(name = "region_label", nullable = false, updatable = false)
    private String regionLabel;

    @Column(name = "destination_id", nullable = false, updatable = false)
    private String destinationId;

    @Column(name = "destination_label", nullable = false, updatable = false)
    private String destinationLabel;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "required_days", nullable = false)
    private int requiredDays;

    @Column(name = "urgency", nullable = false)
    private String urgency;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "incoterm", nullable = false)
    private String incoterm;

    @Column(name = "payment_terms", nullable = false)
    private String paymentTerms;

    @Column(name = "notes", nullable = false)
    private String notes;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "closes_at")
    private Instant closesAt;

    @Column(name = "awarded_supplier_id")
    private String awardedSupplierId;

    @Column(name = "awarded_at")
    private Instant awardedAt;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected RfqEntity() {
        // JPA
    }

    RfqEntity(String ref, String itemNumber, String itemName, String regionKey, String regionLabel,
            String destinationId, String destinationLabel, int qty, int requiredDays, String urgency,
            String priority, String incoterm, String paymentTerms, String notes, String message, UUID createdBy) {
        this.ref = ref;
        this.itemNumber = itemNumber;
        this.itemName = itemName;
        this.regionKey = regionKey;
        this.regionLabel = regionLabel;
        this.destinationId = destinationId;
        this.destinationLabel = destinationLabel;
        this.qty = qty;
        this.requiredDays = requiredDays;
        this.urgency = urgency;
        this.priority = priority;
        this.incoterm = incoterm;
        this.paymentTerms = paymentTerms;
        this.notes = notes == null ? "" : notes;
        this.message = message;
        this.status = Status.draft;
        this.createdBy = createdBy;
    }

    String getRef() {
        return ref;
    }

    String getItemNumber() {
        return itemNumber;
    }

    String getItemName() {
        return itemName;
    }

    String getRegionKey() {
        return regionKey;
    }

    String getRegionLabel() {
        return regionLabel;
    }

    String getDestinationId() {
        return destinationId;
    }

    String getDestinationLabel() {
        return destinationLabel;
    }

    int getQty() {
        return qty;
    }

    void setQty(int qty) {
        this.qty = qty;
    }

    int getRequiredDays() {
        return requiredDays;
    }

    void setRequiredDays(int requiredDays) {
        this.requiredDays = requiredDays;
    }

    String getUrgency() {
        return urgency;
    }

    String getPriority() {
        return priority;
    }

    void setPriority(String priority) {
        this.priority = priority;
    }

    String getIncoterm() {
        return incoterm;
    }

    String getPaymentTerms() {
        return paymentTerms;
    }

    String getNotes() {
        return notes;
    }

    void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    String getAwardedSupplierId() {
        return awardedSupplierId;
    }

    Instant getAwardedAt() {
        return awardedAt;
    }

    void award(String supplierId, Instant at) {
        this.awardedSupplierId = supplierId;
        this.awardedAt = at;
        this.status = Status.awarded;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    UUID getCreatedBy() {
        return createdBy;
    }
}

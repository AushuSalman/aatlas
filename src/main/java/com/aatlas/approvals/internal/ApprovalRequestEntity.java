package com.aatlas.approvals.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** See V18: {@code approval_request}. */
@Entity
@Table(name = "approval_request")
class ApprovalRequestEntity extends TenantScopedEntity {

    public enum Status { pending, approved, rejected }

    @Column(name = "decision_id", nullable = false, updatable = false)
    private UUID decisionId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "approver_role", nullable = false, updatable = false)
    private String approverRole;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "note")
    private String note;

    protected ApprovalRequestEntity() {
        // JPA
    }

    ApprovalRequestEntity(UUID decisionId, UUID requestedBy, String approverRole, BigDecimal amount, String note) {
        this.decisionId = decisionId;
        this.requestedBy = requestedBy;
        this.approverRole = approverRole;
        this.amount = amount;
        this.status = Status.pending;
        this.note = note;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    UUID getRequestedBy() {
        return requestedBy;
    }

    String getApproverRole() {
        return approverRole;
    }

    BigDecimal getAmount() {
        return amount;
    }

    Status getStatus() {
        return status;
    }

    UUID getDecidedBy() {
        return decidedBy;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getNote() {
        return note;
    }

    void decide(Status status, UUID decidedBy, Instant decidedAt, String note) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.note = note;
    }
}

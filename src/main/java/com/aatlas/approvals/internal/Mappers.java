package com.aatlas.approvals.internal;

import com.aatlas.approvals.ApprovalRequest;
import com.aatlas.approvals.ApprovalStatus;
import com.aatlas.decisions.Decision;
import java.util.UUID;

final class Mappers {

    private Mappers() {
    }

    static ApprovalRequest toApprovalRequest(ApprovalRequestEntity e, Decision decision, UUID currentUserId) {
        return new ApprovalRequest(
                e.getId(),
                e.getDecisionId(),
                decision,
                e.getRequestedBy(),
                e.getApproverRole(),
                e.getAmount(),
                ApprovalStatus.fromWire(e.getStatus().name()),
                e.getDecidedBy(),
                e.getDecidedAt(),
                e.getNote(),
                e.getCreatedAt(),
                e.getRequestedBy().equals(currentUserId));
    }
}

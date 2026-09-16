package com.aatlas.approvals;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A request was approved. The module that raised it (via {@link ApprovalRequester#raise})
 * listens for this to finish committing the decision it stood behind - writing the actual
 * deal ({@code decisions.DecisionRecorder#recordSale}/{@code recordPurchase}), which this
 * module never does itself.
 */
public record ApprovalGranted(UUID requestId, UUID decisionId, UUID tenantId, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "approvals.request.approved";
    }
}

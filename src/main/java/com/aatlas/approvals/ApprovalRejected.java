package com.aatlas.approvals;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A request was turned down. {@code decisions.Decision#status} is already {@code rejected}
 * by the time this is published - a raising module listens only if it keeps its own state
 * (an RFQ's award, say) that needs to unwind.
 */
public record ApprovalRejected(UUID requestId, UUID decisionId, UUID tenantId, String note, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "approvals.request.rejected";
    }
}

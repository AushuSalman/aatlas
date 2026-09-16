package com.aatlas.approvals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a raising module gives {@link ApprovalRequester#raise}: a decision it has already
 * recorded as {@link com.aatlas.decisions.DecisionStatus#PENDING} (via {@code
 * decisions.DecisionRecorder#recordPending}), the seat that must sign off, and the order
 * value that decision represents. {@code requestedBy} is not here - the caller is always the
 * signed-in user, read from {@code TenantContext} like every other write in the API.
 */
public record RaiseApprovalRequest(UUID decisionId, String approverRole, BigDecimal amount, String note) {
}

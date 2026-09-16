package com.aatlas.approvals;

import com.aatlas.decisions.Decision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One request for sign-off, with the decision it would commit. The blueprint's {@code
 * approval_request} row, plus {@code decision} embedded - {@code GET /approvals/{id}}'s "one
 * request with the decision behind it" needs both in one response, and every other reader
 * ({@code GET /approvals}) wants the decision's title on the list row too.
 */
@Schema(name = "ApprovalRequest")
public record ApprovalRequest(
        UUID id,
        UUID decisionId,
        Decision decision,
        UUID requestedBy,
        String approverRole,
        BigDecimal amount,
        ApprovalStatus status,
        UUID decidedBy,
        Instant decidedAt,
        String note,
        Instant createdAt,
        /** Whether the signed-in caller raised this request. */
        boolean mine) {
}

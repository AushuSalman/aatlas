package com.aatlas.decisions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Ports {@code recordDecision}'s input (the frontend's {@code Omit<Decision, 'id'|'at'>}).
 * {@code userId}/{@code tenantId}/{@code id}/{@code at}/{@code status} are stamped by the
 * recorder, never accepted from a caller. {@code quote} is carried only for a sell decision
 * with deal context - see {@link QuoteBreakdown}.
 */
public record RecordDecisionRequest(
        @NotNull DecisionKind kind,
        @NotBlank String title,
        String itemNumber,
        String scope,
        BigDecimal recommended,
        BigDecimal applied,
        BigDecimal expectedImpact,
        String impactLabel,
        String detail,
        Integer count,
        QuoteBreakdown quote) {
}

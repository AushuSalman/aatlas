package com.aatlas.decisions;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A recommendation a user acted on. Field for field the frontend's {@code Decision}
 * (intel/decisions.ts), plus {@code status} - see {@link DecisionStatus}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Decision(
        UUID id,
        Instant at,
        DecisionKind kind,
        String title,
        String itemNumber,
        String scope,
        BigDecimal recommended,
        BigDecimal applied,
        BigDecimal expectedImpact,
        String impactLabel,
        String detail,
        Integer count,
        DecisionStatus status) {
}

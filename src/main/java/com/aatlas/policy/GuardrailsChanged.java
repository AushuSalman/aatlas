package com.aatlas.policy;

import com.aatlas.common.event.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's pricing guardrails were saved or reset.
 *
 * <p>Every sell recommendation is clamped by these four numbers, so a change makes the
 * tenant's sell snapshots stale. The engine workers subscribe to this and recompute;
 * nothing is recomputed on the request thread.
 *
 * @param changedBy the user who saved, null for a system reset
 * @param occurredAt from {@code AatlasClock}
 */
public record GuardrailsChanged(
        UUID tenantId,
        UUID changedBy,
        BigDecimal minMarginPct,
        BigDecimal maxDiscountPct,
        BigDecimal maxSpeedPremiumPct,
        BigDecimal maxMarketDeviationPct,
        Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "policy.guardrails.changed";
    }
}

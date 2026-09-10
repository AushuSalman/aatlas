package com.aatlas.policy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The four limits plus who last set them. The first four fields are the frontend's
 * {@code Guardrails} type exactly; {@code updatedAt} and {@code updatedBy} are absent while
 * a tenant is still on the defaults.
 */
@Schema(name = "Guardrails")
record GuardrailsView(
        BigDecimal minMarginPct,
        BigDecimal maxDiscountPct,
        BigDecimal maxSpeedPremiumPct,
        BigDecimal maxMarketDeviationPct,
        Instant updatedAt,
        UUID updatedBy) {

    static GuardrailsView of(GuardrailsEntity row) {
        GuardrailValues v = row.values();
        return new GuardrailsView(v.minMarginPct(), v.maxDiscountPct(), v.maxSpeedPremiumPct(),
                v.maxMarketDeviationPct(), row.getUpdatedAt(), row.getUpdatedBy());
    }

    static GuardrailsView defaults(GuardrailValues v) {
        return new GuardrailsView(v.minMarginPct(), v.maxDiscountPct(), v.maxSpeedPremiumPct(),
                v.maxMarketDeviationPct(), null, null);
    }
}

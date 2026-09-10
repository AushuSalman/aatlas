package com.aatlas.policy.internal;

import java.math.BigDecimal;

/**
 * The four limits, as a value: the shape of {@code seed/guardrails.json}, of the request
 * body, of the history snapshot and of the response. One record, so they cannot drift.
 *
 * <p>Percentages, not fractions: {@code 25} means 25%, as the frontend's
 * {@code Guardrails} type spells it.
 */
record GuardrailValues(
        BigDecimal minMarginPct,
        BigDecimal maxDiscountPct,
        BigDecimal maxSpeedPremiumPct,
        BigDecimal maxMarketDeviationPct) {

    /** Trailing zeros dropped so {@code numeric(5,2)} comes back as {@code 25}, not {@code 25.00}. */
    GuardrailValues normalised() {
        return new GuardrailValues(plain(minMarginPct), plain(maxDiscountPct), plain(maxSpeedPremiumPct),
                plain(maxMarketDeviationPct));
    }

    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}

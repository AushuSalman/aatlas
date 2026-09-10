package com.aatlas.sell.internal.policy;

/** The frontend's {@code Guardrails} in {@code src/lib/intel/guardrails.ts}, field for field. */
public record GuardrailValues(
        double minMarginPct, double maxDiscountPct, double maxSpeedPremiumPct, double maxMarketDeviationPct) {

    /** {@code DEFAULT_GUARDRAILS}: 25 / 15 / 8 / 10, also {@code seed/guardrails.json}. */
    public static final GuardrailValues DEFAULTS = new GuardrailValues(25, 15, 8, 10);
}

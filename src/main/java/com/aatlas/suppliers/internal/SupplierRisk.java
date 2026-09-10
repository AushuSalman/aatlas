package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Fulfilment risk. Mirrors {@code SupplierRisk} in the frontend's {@code intel/buy2.ts}.
 *
 * @param score 0 = no risk, 100 = certain trouble
 * @param level Low under 35, Medium under 60, High otherwise
 * @param capacity High, Medium or Low
 * @param consistency lead-time consistency: High, Medium or Low
 * @param trend Stable, Improving or Worsening
 * @param recentDelayPct positive when delays are rising, negative when they are falling
 * @param computedAt when the engine last ran; absent on a freshly computed value
 */
@Schema(name = "SupplierRisk")
record SupplierRisk(
        int score,
        String level,
        String capacity,
        double leadVarianceDays,
        String consistency,
        String trend,
        double recentDelayPct,
        List<RiskFactor> factors,
        Instant computedAt) {

    RiskSummary summary() {
        return new RiskSummary(score, level);
    }

    SupplierRisk computedAt(Instant at) {
        return new SupplierRisk(score, level, capacity, leadVarianceDays, consistency, trend, recentDelayPct,
                factors, at);
    }
}

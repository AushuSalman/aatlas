package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Fulfilment risk. Mirrors {@code SupplierRisk} in the frontend's {@code intel/buy2.ts}.
 *
 * <p>Every field but {@code factors} and {@code computedAt} is null when the supplier is "not
 * assessed": known only by name, with neither an observed nor a provided on-time rate or
 * defect rate to score. {@code factors} is empty rather than null in that case.
 *
 * @param score 0 = no risk, 100 = certain trouble; null = not assessed
 * @param level Low under 35, Medium under 60, High otherwise; null = not assessed
 * @param capacity High, Medium or Low; null when capacity was never provided or no purchase
 *     volume has been observed
 * @param consistency lead-time consistency: High, Medium or Low; null unless observed
 * @param trend Stable, Improving or Worsening; null unless observed with enough history
 * @param recentDelayPct positive when delays are rising, negative when they are falling; null
 *     unless observed
 * @param computedAt when the engine last ran
 */
@Schema(name = "SupplierRisk")
record SupplierRisk(
        Integer score,
        String level,
        String capacity,
        Double leadVarianceDays,
        String consistency,
        String trend,
        Double recentDelayPct,
        List<RiskFactor> factors,
        Instant computedAt) {

    RiskSummary summary() {
        return new RiskSummary(score, level, score != null);
    }
}

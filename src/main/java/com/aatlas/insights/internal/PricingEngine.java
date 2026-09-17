package com.aatlas.insights.internal;

import com.aatlas.history.PricingMath;
import java.math.BigDecimal;

/**
 * Small shared pricing helpers over {@link PairFacts}. The recommendation, anchor and demand
 * themselves are built once per pair by {@link InsightsDataLoader} (over {@code history}'s
 * {@code BulkModelReader} and {@code PricingMath}); this class only holds the bits every
 * engine below would otherwise repeat.
 */
final class PricingEngine {

    private PricingEngine() {
    }

    /** {@code (price − cost) / price × 100}; null without both, never a fabricated 0. */
    static Double marginPercent(BigDecimal price, BigDecimal cost) {
        return Fmt.dv(PricingMath.marginPct(price, cost));
    }
}

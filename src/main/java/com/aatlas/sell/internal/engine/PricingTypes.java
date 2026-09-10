package com.aatlas.sell.internal.engine;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Internal, double-precision mirrors of {@code src/lib/mock/pricing.ts}'s shapes. These are
 * computation intermediates, not wire types: {@link com.aatlas.sell.internal.dto} converts
 * the numbers a response actually carries to {@code BigDecimal} at the boundary. Kept
 * primitive here so the arithmetic matches the JavaScript double-precision engine bit for
 * bit, per {@code golden/README.md}.
 */
final class PricingTypes {

    private PricingTypes() {
    }

    record Competitor(String name, String domain, double price, double deltaVsCurrent) {
    }

    record DemandModel(
            String level,
            String label,
            double index,
            double movePercent,
            String confidence,
            double confWeight,
            double recentVelocity,
            double expectedVelocity,
            int maxAdjustmentPct,
            String trendDirection,
            int historyDays) {
    }

    /** {@code PricingModel}. {@code competitorMedian} is empty exactly when TypeScript's is null. */
    record PricingModel(
            String item,
            String storeId,
            boolean priceable,
            double cost,
            double currentPrice,
            double optimalPrice,
            double aggressivePrice,
            String recommendedTier,
            double floorPrice,
            double ceilingPrice,
            double peerQ1,
            double peerQ2,
            double peerQ3,
            List<Competitor> competitors,
            OptionalDouble competitorMedian,
            double msaMult,
            String msaMode,
            DemandModel demand,
            String segment,
            int totalTransactions,
            int totalCompanies,
            double observedMin,
            double observedMax) {
    }
}

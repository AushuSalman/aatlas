package com.aatlas.suppliers.internal;

import static com.aatlas.suppliers.internal.Js.clamp;
import static com.aatlas.suppliers.internal.Js.round1;

import com.aatlas.common.seed.Seeded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fulfilment risk. A port of {@code supplierRisk} in the frontend's {@code intel/buy2.ts} -
 * the only function taken from that module, per the module boundary: {@code buy} itself is
 * built elsewhere.
 */
final class RiskScoring {

    private RiskScoring() {
    }

    record Input(String supplierId, double leadDays, double otifPct, double defectPct) {
    }

    /**
     * @param ratingForFactor the persisted rating and review count, when known, added as the
     *     "Buyer rating" factor exactly as the frontend does when {@code supplierProfile(id)}
     *     resolves
     */
    static SupplierRisk supplierRisk(Input s, RatingSummary ratingForFactor, Instant computedAt) {
        String seed = "srisk:" + s.supplierId();
        double capRoll = Seeded.rand(seed, "cap");
        String capacity = capRoll > 0.55 ? "High" : capRoll > 0.22 ? "Medium" : "Low";
        double leadVarianceDays = round1(Math.max(0.5, s.leadDays() * Seeded.randRange(seed, "var", 0.06, 0.3)));
        double ratio = leadVarianceDays / Math.max(1, s.leadDays());
        String consistency = ratio < 0.12 ? "High" : ratio < 0.2 ? "Medium" : "Low";
        double trendRoll = Seeded.rand(seed, "trend");
        String trend = trendRoll > 0.72 ? "Improving" : trendRoll > 0.32 ? "Stable" : "Worsening";
        double recentDelayPct = "Worsening".equals(trend)
                ? round1(Seeded.randRange(seed, "d", 6, 18))
                : "Improving".equals(trend)
                        ? -round1(Seeded.randRange(seed, "d", 2, 8))
                        : round1(Seeded.randRange(seed, "d", -2, 3));

        int score = (int) Math.round(clamp(
                (100 - s.otifPct()) * 1.7
                        + ratio * 60
                        + ("Low".equals(capacity) ? 12 : "Medium".equals(capacity) ? 4 : 0)
                        + ("Worsening".equals(trend) ? 10 : "Improving".equals(trend) ? -4 : 0)
                        + s.defectPct() * 2.5,
                3, 95));
        String level = score < 35 ? "Low" : score < 60 ? "Medium" : "High";

        List<RiskFactor> factors = new ArrayList<>();
        factors.add(new RiskFactor("On-time fulfilment", Js.toFixed(s.otifPct(), 1) + "%", s.otifPct() >= 90));
        factors.add(new RiskFactor("Historical delay", Js.toFixed(100 - s.otifPct(), 1) + "%", s.otifPct() >= 90));
        factors.add(new RiskFactor("Capacity", capacity, !"Low".equals(capacity)));
        factors.add(new RiskFactor("Lead-time consistency",
                consistency + " (±" + Js.toFixed(leadVarianceDays, 0) + "d)", !"Low".equals(consistency)));
        String recentValue = "Stable".equals(trend)
                ? "Stable"
                : trend + " (" + (recentDelayPct > 0 ? "↑" : "↓") + " "
                        + Js.toFixed(Math.abs(recentDelayPct), 0) + "% delays)";
        factors.add(new RiskFactor("Recent performance", recentValue, !"Worsening".equals(trend)));
        if (ratingForFactor != null) {
            factors.add(new RiskFactor("Buyer rating",
                    Js.toFixed(ratingForFactor.rating(), 1) + " ★ (" + ratingForFactor.reviewCount()
                            + " reviews)",
                    ratingForFactor.rating() >= 4));
        }

        return new SupplierRisk(score, level, capacity, leadVarianceDays, consistency, trend, recentDelayPct,
                List.copyOf(factors), computedAt);
    }

    record RatingSummary(double rating, int reviewCount) {
    }
}

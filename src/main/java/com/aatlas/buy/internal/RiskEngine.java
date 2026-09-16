package com.aatlas.buy.internal;

import com.aatlas.buy.SupplierRisk;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.List;

/**
 * Fulfilment risk: a port of {@code supplierRisk} in the frontend's {@code intel/buy2.ts}.
 *
 * <p>Both {@code terms.ts} and {@code buy2.ts} feed this one risk model on the frontend; the
 * {@code suppliers} module (wave 1) ported it too, behind its own REST endpoints only - see
 * {@link com.aatlas.buy.SupplierRisk}'s Javadoc for the stand-in rule this follows.
 *
 * <p>TODO(merge): consider depending on suppliers' public reader if one exists after merge.
 */
final class RiskEngine {

    private RiskEngine() {
    }

    /** What the risk model reads: the same fields whether they come from a Buy evaluation or the supplier panel. */
    record RiskInput(String supplierId, int leadDays, double otifPct, double defectPct) {
    }

    static SupplierRisk supplierRisk(CatalogGateway.LogisticsRef ref, RiskInput s, SupplierGateway.SupplierRow supplier) {
        String seed = "srisk:" + s.supplierId();
        double capRoll = Seeded.rand(seed, "cap");
        String capacity = capRoll > 0.55 ? "High" : capRoll > 0.22 ? "Medium" : "Low";
        double leadVarianceDays = Js.round1(Math.max(0.5, s.leadDays() * Seeded.randRange(seed, "var", 0.06, 0.3)));
        double ratio = leadVarianceDays / Math.max(1, s.leadDays());
        String consistency = ratio < 0.12 ? "High" : ratio < 0.2 ? "Medium" : "Low";
        double trendRoll = Seeded.rand(seed, "trend");
        String trend = trendRoll > 0.72 ? "Improving" : trendRoll > 0.32 ? "Stable" : "Worsening";
        double recentDelayPct = "Worsening".equals(trend)
                ? Js.round1(Seeded.randRange(seed, "d", 6, 18))
                : "Improving".equals(trend)
                        ? -Js.round1(Seeded.randRange(seed, "d", 2, 8))
                        : Js.round1(Seeded.randRange(seed, "d", -2, 3));

        int score = (int) Math.round(Js.clamp(
                (100 - s.otifPct()) * 1.7 + ratio * 60
                        + ("Low".equals(capacity) ? 12 : "Medium".equals(capacity) ? 4 : 0)
                        + ("Worsening".equals(trend) ? 10 : "Improving".equals(trend) ? -4 : 0)
                        + s.defectPct() * 2.5,
                3, 95));
        String level = score < 35 ? "Low" : score < 60 ? "Medium" : "High";

        List<SupplierRisk.Factor> factors = new ArrayList<>();
        factors.add(new SupplierRisk.Factor("On-time fulfilment", Js.toFixed(s.otifPct(), 1) + "%", s.otifPct() >= 90));
        factors.add(new SupplierRisk.Factor("Historical delay", Js.toFixed(100 - s.otifPct(), 1) + "%", s.otifPct() >= 90));
        factors.add(new SupplierRisk.Factor("Capacity", capacity, !"Low".equals(capacity)));
        factors.add(new SupplierRisk.Factor("Lead-time consistency",
                consistency + " (±" + Js.toFixed(leadVarianceDays, 0) + "d)", !"Low".equals(consistency)));
        String recentValue = "Stable".equals(trend)
                ? "Stable"
                : trend + " (" + (recentDelayPct > 0 ? "↑" : "↓") + " "
                        + Js.toFixed(Math.abs(recentDelayPct), 0) + "% delays)";
        factors.add(new SupplierRisk.Factor("Recent performance", recentValue, !"Worsening".equals(trend)));

        if (supplier != null) {
            RatingEngine.Profile profile = RatingEngine.profileFor(ref, supplier.id(), supplier.country(),
                    supplier.leadTimeDays(), supplier.otifPct(), supplier.priceIndex(),
                    supplier.defectPct());
            factors.add(new SupplierRisk.Factor("Buyer rating",
                    Js.toFixed(profile.rating(), 1) + " ★ (" + profile.reviewCount() + " reviews)",
                    profile.rating() >= 4));
        }

        return new SupplierRisk(score, level, capacity, leadVarianceDays, consistency, trend, recentDelayPct, factors);
    }
}

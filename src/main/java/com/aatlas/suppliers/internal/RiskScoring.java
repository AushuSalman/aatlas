package com.aatlas.suppliers.internal;

import static com.aatlas.suppliers.internal.Js.clamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fulfilment risk, rewritten from a port of {@code supplierRisk} in the frontend's
 * {@code intel/buy2.ts} to score only what the tenant's own data shows.
 *
 * <p>Two modes, chosen by the caller (which knows how many purchase orders the platform has
 * actually received for this supplier):
 *
 * <ul>
 *   <li><b>Observed</b> ({@code input.observed() == true}): the on-time rate, lead-time
 *       variance and consistency, the recent-delay trend and the capacity check all come from
 *       {@code history.PurchaseHistory} - real purchase orders the tenant received.
 *   <li><b>Provided-only</b> (fewer than five received purchase orders in the trailing
 *       twelve months): only the supplier's own on-time rate and defect rate are used, if
 *       they were ever supplied. Lead-time variance, consistency, trend and recent delay stay
 *       {@code null} - there is not enough history to say anything about them.
 * </ul>
 *
 * <p>A supplier with neither an observed nor a provided on-time rate <em>and</em> neither an
 * observed nor a provided defect rate is "not assessed": {@code score} and {@code level} are
 * {@code null} and {@code factors} is empty, never a placeholder score that reads as a fact.
 */
final class RiskScoring {

    private RiskScoring() {
    }

    /**
     * Everything the formula needs, already resolved by the caller from real data.
     *
     * @param observed whether the supplier has at least five received purchase orders in the
     *     trailing twelve months; gates which of the other fields the formula trusts
     * @param otifPct the observed on-time rate (w12) when {@code observed}, else the
     *     supplier's own provided on-time rate; null when neither exists
     * @param defectPct the supplier's provided defect rate; never observed
     * @param leadAvgDays the observed average order-to-dock days; null unless observed
     * @param leadSdDays the observed lead-time standard deviation; null unless observed
     * @param trend Improving, Worsening or Stable from the last six months of observed OTIF;
     *     null unless observed and there is at least three received orders in each half
     * @param recentDelayPct the observed share of late deliveries in the trailing 90 days;
     *     null unless observed and measurable
     * @param capacity High, Medium or Low from observed monthly volume against
     *     {@code supplier_terms.capacity_units_month}; null when either side is unknown
     */
    record Input(
            boolean observed,
            Double otifPct,
            Double defectPct,
            Double leadAvgDays,
            Double leadSdDays,
            String trend,
            Double recentDelayPct,
            String capacity,
            Double avgMonthlyUnits) {
    }

    /**
     * @param ratingForFactor the persisted rating and review count, when known, added as the
     *     "Buyer rating" factor
     */
    record RatingSummary(double rating, int reviewCount) {
    }

    static SupplierRisk supplierRisk(Input in, RatingSummary ratingForFactor, Instant computedAt) {
        if (in.otifPct() == null && in.defectPct() == null) {
            return new SupplierRisk(null, null, null, null, null, null, null, List.of(), computedAt);
        }

        double ratio = in.observed() && in.leadSdDays() != null && in.leadAvgDays() != null
                ? in.leadSdDays() / Math.max(1, in.leadAvgDays())
                : 0;
        String consistency = in.observed() && in.leadSdDays() != null
                ? (ratio < 0.12 ? "High" : ratio < 0.2 ? "Medium" : "Low")
                : null;
        String trend = in.observed() ? in.trend() : null;
        Double recentDelayPct = in.observed() ? in.recentDelayPct() : null;

        double otifTerm = in.otifPct() == null ? 0 : (100 - in.otifPct()) * 1.7;
        double capacityTerm = "Low".equals(in.capacity()) ? 12 : "Medium".equals(in.capacity()) ? 4 : 0;
        double trendTerm = "Worsening".equals(trend) ? 10 : "Improving".equals(trend) ? -4 : 0;
        double defectTerm = in.defectPct() == null ? 0 : in.defectPct() * 2.5;

        int score = (int) Math.round(
                clamp(otifTerm + ratio * 60 + capacityTerm + trendTerm + defectTerm, 3, 95));
        String level = score < 35 ? "Low" : score < 60 ? "Medium" : "High";

        List<RiskFactor> factors = new ArrayList<>();
        if (in.otifPct() != null) {
            factors.add(new RiskFactor("On-time fulfilment", Js.toFixed(in.otifPct(), 1) + "%", in.otifPct() >= 90));
            factors.add(
                    new RiskFactor("Historical delay", Js.toFixed(100 - in.otifPct(), 1) + "%", in.otifPct() >= 90));
        }
        if (in.capacity() != null) {
            factors.add(new RiskFactor("Capacity", in.capacity(), !"Low".equals(in.capacity())));
        }
        if (consistency != null) {
            factors.add(new RiskFactor("Lead-time consistency",
                    consistency + " (±" + Js.toFixed(in.leadSdDays(), 0) + "d)", !"Low".equals(consistency)));
        }
        if (trend != null) {
            String recentValue = "Stable".equals(trend) || recentDelayPct == null
                    ? trend
                    : trend + " (" + (recentDelayPct > 0 ? "↑" : "↓") + " "
                            + Js.toFixed(Math.abs(recentDelayPct), 0) + "% delays)";
            factors.add(new RiskFactor("Recent performance", recentValue, !"Worsening".equals(trend)));
        }
        if (ratingForFactor != null) {
            factors.add(new RiskFactor("Buyer rating",
                    Js.toFixed(ratingForFactor.rating(), 1) + " ★ (" + ratingForFactor.reviewCount()
                            + " reviews)",
                    ratingForFactor.rating() >= 4));
        }

        return new SupplierRisk(
                score, level, in.capacity(), in.observed() ? in.leadSdDays() : null, consistency, trend,
                recentDelayPct, List.copyOf(factors), computedAt);
    }
}

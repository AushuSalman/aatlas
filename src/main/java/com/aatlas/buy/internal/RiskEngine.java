package com.aatlas.buy.internal;

import com.aatlas.buy.SupplierRisk;
import com.aatlas.history.PurchaseHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Fulfilment risk: spec-A S3.6. Observed (label {@code observed}) when the supplier has at
 * least five received purchase orders in the trailing twelve months - lead-time variance,
 * consistency and the six-month OTIF trend all come from {@link PurchaseHistory}; below that
 * threshold only the file OTIF/defect rate (and capacity, when terms give one) score the
 * supplier, and when neither is known at all the supplier is "not assessed" - never a
 * fabricated 95/High for a name the platform knows nothing about yet.
 */
final class RiskEngine {

    private RiskEngine() {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int OBSERVED_MIN_RECEIVED = 5;

    /** Everything the formula reads, already resolved by the caller (one per supplier per request). */
    record Input(
            /** The supplier's trailing-12m purchase record, tenant-wide (any item). */
            PurchaseHistory.PoStats w12,
            /** Six-month OTIF trend, oldest first; empty when there is no purchase history at all. */
            List<PurchaseHistory.MonthOtif> otifTrend,
            /** The supplier's received POs in the trailing 90 days, tenant-wide; null with none. */
            PurchaseHistory.PoStats w90,
            /** File OTIF, used only when the observed threshold is not met. */
            BigDecimal fileOtifPct,
            /** File defect rate - always used, observed or not: purchase history carries no quality signal. */
            BigDecimal defectPct,
            /** The supplier's own average monthly purchase volume, tenant-wide (units12m / 12); null with none. */
            BigDecimal avgMonthlyUnits,
            /** {@code supplier_terms.capacity_units_month}; null when not provided. */
            Integer capacityUnitsMonth) {
    }

    static SupplierRisk supplierRisk(Input in) {
        boolean observed = in.w12() != null && in.w12().received() >= OBSERVED_MIN_RECEIVED;
        BigDecimal otif = observed ? in.w12().otifPct() : in.fileOtifPct();
        BigDecimal defect = in.defectPct();

        if (otif == null && defect == null) {
            return SupplierRisk.notAssessed();
        }

        BigDecimal leadVarianceDays = null;
        String consistency = null;
        double ratio = 0;
        if (observed && in.w12().leadSdDays() != null && in.w12().avgLeadDays() != null) {
            leadVarianceDays = in.w12().leadSdDays();
            double avgLead = Math.max(1, in.w12().avgLeadDays().doubleValue());
            ratio = leadVarianceDays.doubleValue() / avgLead;
            consistency = ratio < 0.12 ? "High" : ratio < 0.2 ? "Medium" : "Low";
        }

        String trend = observed ? trendFrom(in.otifTrend()) : null;

        BigDecimal recentDelayPct = null;
        if (observed && in.w90() != null && in.w90().received() > 0 && in.w90().otifPct() != null) {
            recentDelayPct = HUNDRED.subtract(in.w90().otifPct()).setScale(1, RoundingMode.HALF_UP);
        }

        String capacity = null;
        if (in.capacityUnitsMonth() != null && in.capacityUnitsMonth() > 0 && in.avgMonthlyUnits() != null) {
            double util = in.avgMonthlyUnits().doubleValue() / in.capacityUnitsMonth();
            capacity = util > 0.8 ? "Low" : util > 0.5 ? "Medium" : "High";
        }

        double score = (otif == null ? 0 : (100 - otif.doubleValue()) * 1.7)
                + ratio * 60
                + ("Low".equals(capacity) ? 12 : "Medium".equals(capacity) ? 4 : 0)
                + ("Worsening".equals(trend) ? 10 : "Improving".equals(trend) ? -4 : 0)
                + (defect == null ? 0 : defect.doubleValue() * 2.5);
        int scoreOut = (int) Math.round(Js.clamp(score, 3, 95));
        String level = scoreOut < 35 ? "Low" : scoreOut < 60 ? "Medium" : "High";

        List<SupplierRisk.Factor> factors = new ArrayList<>();
        if (otif != null) {
            factors.add(new SupplierRisk.Factor(observed ? "On-time fulfilment (observed)" : "On-time fulfilment (file)",
                    Js.toFixed(otif.doubleValue(), 1) + "%", otif.doubleValue() >= 90));
        }
        if (defect != null) {
            factors.add(new SupplierRisk.Factor("Defect rate", Js.toFixed(defect.doubleValue(), 2) + "%",
                    defect.doubleValue() <= 1));
        }
        if (capacity != null) {
            factors.add(new SupplierRisk.Factor("Capacity", capacity, !"Low".equals(capacity)));
        }
        if (consistency != null) {
            factors.add(new SupplierRisk.Factor("Lead-time consistency",
                    consistency + " (±" + Js.toFixed(leadVarianceDays.doubleValue(), 0) + "d)",
                    !"Low".equals(consistency)));
        }
        if (trend != null) {
            factors.add(new SupplierRisk.Factor("Recent performance", trend, !"Worsening".equals(trend)));
        }

        return new SupplierRisk(scoreOut, level, capacity, leadVarianceDays, consistency, trend, recentDelayPct,
                List.copyOf(factors));
    }

    /**
     * Last three months' OTIF against the prior three, from the six-month trend. Null (never
     * "Stable") unless at least three POs were received in each half.
     */
    private static String trendFrom(List<PurchaseHistory.MonthOtif> trend) {
        if (trend == null || trend.size() < 6) {
            return null;
        }
        List<PurchaseHistory.MonthOtif> prior = trend.subList(0, 3);
        List<PurchaseHistory.MonthOtif> recent = trend.subList(3, 6);
        BigDecimal priorAvg = weightedOtif(prior);
        BigDecimal recentAvg = weightedOtif(recent);
        if (priorAvg == null || recentAvg == null) {
            return null;
        }
        double delta = recentAvg.subtract(priorAvg).doubleValue();
        return delta >= 3 ? "Improving" : delta <= -3 ? "Worsening" : "Stable";
    }

    private static BigDecimal weightedOtif(List<PurchaseHistory.MonthOtif> months) {
        long totalReceived = months.stream().mapToLong(PurchaseHistory.MonthOtif::received).sum();
        if (totalReceived < 3) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (PurchaseHistory.MonthOtif m : months) {
            if (m.otifPct() != null && m.received() > 0) {
                weighted = weighted.add(m.otifPct().multiply(BigDecimal.valueOf(m.received())));
            }
        }
        return weighted.divide(BigDecimal.valueOf(totalReceived), 2, RoundingMode.HALF_UP);
    }
}

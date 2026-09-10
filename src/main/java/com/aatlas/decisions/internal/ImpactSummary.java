package com.aatlas.decisions.internal;

import java.util.List;

/** Ports {@code platform/types.ts}'s {@code ImpactSummary}. */
public record ImpactSummary(
        String side,
        int deals,
        int followedDeals,
        double captureRatePct,
        double realisedProfit,
        double gained,
        double lost,
        double potentialProfit,
        double netImpact,
        List<MonthPoint> byMonth) {

    public record MonthPoint(String label, double gained, double lost) {
    }
}

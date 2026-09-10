package com.aatlas.analytics;

import java.util.List;

/**
 * The buy side of "what has the tool been worth", reduced from the procurement ledger's
 * trailing twelve months - the same rows the Buying insights dashboard's 12-month view reads,
 * so a figure quoted here and one quoted there can never disagree.
 *
 * <p>Shaped to slot directly into {@code decisions}' {@code ImpactSummary} (ported from the
 * frontend's {@code platform/api.ts}, {@code summariseBuy}): {@code lines} is
 * {@code ImpactSummary.deals}, {@code savedTotal}/{@code leakedTotal} are
 * {@code gained}/{@code lost}, and {@code byMonth} is {@code ImpactSummary.byMonth} verbatim.
 *
 * @param lines purchase order lines in the trailing 12 months
 * @param captureRatePct percent of lines that landed at or under target
 * @param savedTotal realised saving against baseline, across the window
 * @param leakedTotal spend above target on lines that ignored the recommendation
 * @param byMonth one point per calendar month bucket, oldest first
 */
public record BuyImpactSummary(int lines, double captureRatePct, double savedTotal, double leakedTotal,
        List<MonthPoint> byMonth) {

    public record MonthPoint(String label, double gained, double lost) {
    }
}

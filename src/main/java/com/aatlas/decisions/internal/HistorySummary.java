package com.aatlas.decisions.internal;

import com.aatlas.decisions.Decision;
import java.util.List;

/** Ports {@code intel/history.ts}'s {@code HistorySummary}. */
public record HistorySummary(
        List<HistoryRow> rows,
        List<Decision> decisions,
        int total,
        int followed,
        int adoptionPct,
        double gained,
        double lost,
        double net) {
}

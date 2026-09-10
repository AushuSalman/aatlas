package com.aatlas.decisions.internal;

/** {@code GET /history/summary}: the five headline figures, without the row/decision lists. */
public record HistorySummaryView(int decisions, int followedPct, double gained, double lost, double net) {
}

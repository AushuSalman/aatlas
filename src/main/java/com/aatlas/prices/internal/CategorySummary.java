package com.aatlas.prices.internal;

import java.math.BigDecimal;

/**
 * One category in the suggestions: the margin in force (an override or the benchmark) and
 * the reference band behind it.
 */
record CategorySummary(String category, BigDecimal targetMarginPct, BigDecimal benchmarkTargetPct,
        BigDecimal lowMarginPct, BigDecimal highMarginPct, String note, int items, boolean overridden) {

    CategorySummary plusOne() {
        return new CategorySummary(category, targetMarginPct, benchmarkTargetPct, lowMarginPct, highMarginPct, note,
                items + 1, overridden);
    }
}

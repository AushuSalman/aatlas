package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the tenant really earns, item by item, over the last twelve months - the raw material of
 * the learned target margin ({@link DynamicMargin}). Cached per tenant and day; evicted with the
 * rest of the history caches after every import.
 */
public interface MarginProfiles {

    /** Every item sold in the twelve months up to {@code today}, tenant-wide. */
    Profile profile(LocalDate today);

    /**
     * One item's realised margin.
     *
     * @param lines invoice lines in the window
     * @param avgPrice quantity-weighted selling price
     * @param marginPct {@code (revenue − cost) / revenue × 100} over lines carrying a cost; else the
     *     average price against the item's cost on the price list; null with neither
     * @param lowMarginPct the 10th percentile of the line margins, null without costed lines
     */
    record ItemMargin(UUID productId, String category, long lines, BigDecimal avgPrice, BigDecimal marginPct,
            BigDecimal lowMarginPct) {
    }

    record Profile(LocalDate asOf, List<ItemMargin> items) {

        public static Profile empty(LocalDate today) {
            return new Profile(today, List.of());
        }
    }
}

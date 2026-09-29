package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Open-market prices for buying ({@code buy_market_benchmarks}): what a unit costs in bulk lots,
 * as the last "Retail and bulk check" found it. Read by the buy recommendation as market evidence.
 */
public interface BuyBenchmarks {

    /** Older than this and a bulk price is no longer evidence. */
    int MAX_AGE_DAYS = 90;

    /** The latest bulk-lot benchmark within {@link #MAX_AGE_DAYS}, or empty. */
    Optional<Bulk> bulk(UUID productId, LocalDate today);

    /**
     * @param listings how many lots the figure rests on
     * @param sources  the providers that found them, e.g. {@code ebay}
     */
    record Bulk(BigDecimal medianPerUnit, BigDecimal lowPerUnit, int listings, String sources, LocalDate observedAt) {
    }
}

package com.aatlas.prices.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /prices/{item}}: what the price list says, what the ladder resolves, and the
 * rows behind them.
 *
 * @param current null when no price-list row exists for the pair
 */
record PriceDetail(String item, String store, String currency, Current current, Ladder ladder, List<HistoryRow> history) {

    /**
     * The current price-list row(s).
     *
     * @param source the list price's source, else the cost's
     * @param priceListSource the list price's source
     */
    record Current(BigDecimal listPrice, String listPriceSource, BigDecimal cost, String costSource, String source,
            String priceListSource, LocalDate effectiveFrom, boolean storeSpecific) {
    }

    record Figure(BigDecimal value, String source, LocalDate asOf) {
    }

    /** The ladder's answers; either may be null. */
    record Ladder(Figure price, Figure cost) {
    }

    record HistoryRow(LocalDate effectiveFrom, String store, BigDecimal listPrice, BigDecimal cost, String source,
            UUID setBy, Map<String, Object> basis, Instant createdAt, UUID writeId) {
    }
}

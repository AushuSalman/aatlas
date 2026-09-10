package com.aatlas.decisions;

import java.math.BigDecimal;

/**
 * The customer-quote breakdown behind a sell decision. Field for field the frontend's
 * {@code DealQuote} ({@code platform/deal.ts}, {@code quoteForDeal}).
 */
public record QuoteBreakdown(
        int qty,
        BigDecimal volumeBreakPct,
        Integer nextBreakAt,
        BigDecimal nextBreakPct,
        BigDecimal customerDiscountPct,
        BigDecimal bookOptimal,
        BigDecimal bookAggressive,
        BigDecimal optimal,
        BigDecimal aggressive,
        BigDecimal recommended,
        String recommendedTier,
        boolean clampedByFloor,
        BigDecimal effectiveDiscountPct) {
}

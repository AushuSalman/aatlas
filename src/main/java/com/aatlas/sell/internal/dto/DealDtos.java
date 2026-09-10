package com.aatlas.sell.internal.dto;

import java.math.BigDecimal;

/** Wire records mirroring {@code src/lib/platform/deal.ts}'s {@code DealQuote}. */
public final class DealDtos {

    private DealDtos() {
    }

    public record DealQuoteDto(
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
}

package com.aatlas.prices.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /prices/suggestions}.
 *
 * @param regionalIndex the multiplier the store's price parity applies; 1.000 tenant-wide
 * @param truncated true when more than {@link PricesService#MAX_ROWS} items qualified
 */
record SuggestionsResponse(String scope, String store, String currency, BigDecimal regionalIndex,
        List<CategorySummary> categories, List<SuggestionEngine.SuggestionRow> rows, Summary summary,
        boolean truncated) {

    record Summary(int rows, int priceable, int unpriceable, int missingCost) {
    }
}

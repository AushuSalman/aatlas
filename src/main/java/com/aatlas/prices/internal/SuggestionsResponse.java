package com.aatlas.prices.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /prices/suggestions}.
 *
 * @param regionalIndex the multiplier the store's price parity applies: 1.000 tenant-wide, with
 *                      the model's local-market step off, or for a branch priced nationally
 * @param truncated true when more than {@link PricesService#MAX_ROWS} items qualified
 * @param model the tenant's pricing model as the wizard ran it
 */
record SuggestionsResponse(String scope, String store, String currency, BigDecimal regionalIndex,
        List<CategorySummary> categories, List<SuggestionEngine.SuggestionRow> rows, Summary summary,
        boolean truncated, Model model) {

    /**
     * @param marketGaps how many priceable rows were priced to a market gap: credible
     *                   competitors far from the item's own price, which the suggestion followed
     */
    record Summary(int rows, int priceable, int unpriceable, int missingCost, int marketGaps) {
    }

    /**
     * @param on how many of the model's toggles are on
     * @param total how many toggles the model has
     * @param learning true when at least one suggestion leaned on the tenant's own past
     *                 decisions; false with the step off, no usable decisions, or a ledger that
     *                 could not be read
     */
    record Model(int on, int total, boolean learning) {
    }
}

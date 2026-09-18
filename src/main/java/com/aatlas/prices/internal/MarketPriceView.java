package com.aatlas.prices.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /prices/{item}/market-research}: what the open web says this item goes for,
 * with the AI's own summary and every source it came from - never a number this frontend is
 * told to trust without showing where it came from.
 *
 * @param suggestedPrice the median of every dollar figure found; null when none was
 * @param amountsFound every figure the scan found, for the "$2 - $8, 4 mentions" kind of
 *     context a single median throws away
 * @param note the search's own synthesis, or why nothing was found
 */
@Schema(name = "MarketPriceView")
record MarketPriceView(String item, String description, String query, BigDecimal suggestedPrice,
        List<BigDecimal> amountsFound, String note, List<Source> sources) {

    @Schema(name = "MarketPriceSource")
    record Source(String title, String url) {
    }
}

package com.aatlas.sell;

import java.util.List;
import java.util.Map;

/**
 * One (item, store) line as the bulk sell plan needs it: the same twenty-four fields
 * {@code bulk.SellLine} carries, plus where each figure came from and which sections have
 * no data behind them.
 *
 * <p>This is the seam the bulk module prices baskets through, and it is additive-only: a
 * field may be added, never removed or renamed.
 *
 * @param cost 0 when no cost is on file; {@code sources.get("cost")} says so and {@code locked}
 *     carries {@code margin}
 * @param inventoryUnits 0 when there is no stock count; {@code locked} carries {@code inventory}
 * @param sources one of the {@code com.aatlas.history.Resolved} labels per figure:
 *     {@code cost}, {@code currentPrice}, {@code anchor}, {@code units}, {@code inventory}
 * @param locked section keys the UI must hide: {@code margin}, {@code inventory},
 *     {@code demand}, {@code forecast}, {@code competitors}
 */
public record SellLineView(
        String itemNumber,
        String storeId,
        boolean priceable,
        String name,
        String description,
        String storeLabel,
        String category,
        double cost,
        double currentPrice,
        double recommended,
        double stretchPrice,
        double marginFloor,
        double currentMarginPct,
        double expectedMarginPct,
        double elasticity,
        double monthlyUnits,
        double inventoryUnits,
        double inventoryValue,
        double weeksOfCover,
        double monthlyOpportunity,
        String demandLevel,
        double demandPct,
        int confidence,
        String confidenceLabel,
        Map<String, String> sources,
        List<String> locked) {
}

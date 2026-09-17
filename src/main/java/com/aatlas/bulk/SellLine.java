package com.aatlas.bulk;

import java.util.List;
import java.util.Map;

/**
 * Enough of the frontend's {@code SellIntel} (src/lib/intel/sell.ts) to run and render a
 * bulk sell strategy for one (item, store) pair - sourced from {@code sell.SellLines},
 * the real seam into the sell module's own pricing engine, so a basket priced in bulk and
 * a line priced alone can never disagree. {@code getSellIntel} also derives a six-step
 * pricing chain, a timeline, a now-vs-wait recommendation and competitor detail; none of
 * that is needed to run {@code bulkSellPlan}'s three strategies or read by the bulk
 * sell/buy pages (verified against {@code src/app/app/sell/bulk/page.tsx}, which reads
 * only {@code description}, {@code monthlyUnits} and {@code marginFloor} off a line's
 * nested intel), so this record omits it rather than carry dead weight.
 *
 * @param priceable false when this store has no sales history for the item
 * @param cost landed unit cost
 * @param currentPrice what this store charges today
 * @param recommended the optimal price ({@code getPricingModel.optimalPrice})
 * @param stretchPrice the aggressive price ({@code getPricingModel.aggressivePrice})
 * @param marginFloor the 25% hard margin floor in dollars
 * @param currentMarginPct gross margin at {@code currentPrice}, percent
 * @param expectedMarginPct gross margin at {@code recommended}, percent
 * @param elasticity own-price elasticity coefficient (negative)
 * @param monthlyUnits units this store moves of this item in a month
 * @param inventoryUnits units on hand
 * @param inventoryValue {@code inventoryUnits * cost}
 * @param weeksOfCover inventory on hand, in weeks of the monthly rate
 * @param monthlyOpportunity expected extra monthly gross profit at the recommendation
 * @param demandLevel {@code high}, {@code medium}, {@code low} or {@code none}
 * @param demandPct the demand signal's move percent, 0 when there is none
 * @param confidence 55-97, from how much evidence sits behind the number
 * @param confidenceLabel {@code High} (&gt;=85), {@code Medium} (&gt;=70) or {@code Low}
 * @param sources one of the {@code com.aatlas.history.Resolved} labels per figure -
 *     {@code cost}, {@code currentPrice}, {@code anchor}, {@code units}, {@code inventory} -
 *     straight off {@code sell.SellLineView}
 * @param locked section keys the UI must hide: {@code margin}, {@code inventory},
 *     {@code demand}, {@code forecast}, {@code competitors}
 */
public record SellLine(
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

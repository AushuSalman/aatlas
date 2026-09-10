package com.aatlas.bulk;

/**
 * Enough of the frontend's {@code SellIntel} (src/lib/intel/sell.ts) to run and render a
 * bulk sell strategy for one (item, store) pair.
 *
 * <p><b>Stand-in.</b> {@code TODO(merge): replace with the sell module's public
 * SellIntel reader.} The real {@code getSellIntel} also derives a six-step pricing
 * chain, a twelve-month-back/ninety-day-forward timeline, a now-vs-wait recommendation
 * and competitor detail; none of that is needed to run {@code bulkSellPlan}'s three
 * strategies or read by the bulk sell/buy pages (verified against
 * {@code src/app/app/sell/bulk/page.tsx}, which reads only {@code description},
 * {@code monthlyUnits} and {@code marginFloor} off a line's nested intel), so this
 * record omits it rather than fabricate it. Every field it does carry is a real,
 * deterministic computation - the same seeded arithmetic {@code getPricingModel} and
 * {@code getSellIntel} use - not a placeholder value.
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
        String confidenceLabel) {
}

package com.aatlas.bulk;

import java.util.List;

/**
 * Enough of the frontend's {@code BuyIntel} (src/lib/intel/buy.ts) to run and render a
 * bulk buy strategy for one item into one region - sourced from {@code buy.BuyIntelReader},
 * the real seam into the buy module's own recommendation engine. The real
 * {@code getBuyIntel} also derives a pricing chain, a buy-now-vs-wait call and a
 * negotiation message; none of that is read by {@code bulkBuyPlan} or by
 * {@code src/app/app/buy/bulk/page.tsx} (which reads {@code suppliers}, {@code incumbent}
 * and the top-level fields below only), so it is left out rather than carried as dead
 * weight.
 *
 * @param suppliers every evaluated supplier, cheapest all-in first
 * @param incumbent the supplier currently holding the line
 * @param recommendedSupplier the lowest effective cost among acceptable suppliers
 * @param cheapestQuoted the lowest landed cost, which may carry more risk
 * @param annualVolume the region's yearly volume of this item, the base for savings math
 */
public record BuyLine(
        String itemNumber,
        String name,
        boolean priceable,
        String regionKey,
        String regionLabel,
        String destinationId,
        String destinationLabel,
        int qty,
        double currentCost,
        double targetCost,
        double savingPerUnit,
        double savingPct,
        double annualVolume,
        List<SupplierEval> suppliers,
        SupplierEval incumbent,
        SupplierEval recommendedSupplier,
        SupplierEval cheapestQuoted) {
}

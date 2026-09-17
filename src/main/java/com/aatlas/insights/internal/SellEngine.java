package com.aatlas.insights.internal;

/**
 * The per-pair sell figures every screen in this module aggregates: current and recommended
 * price, monthly/annual volume, inventory position and the monthly gross-profit opportunity
 * the recommendation would add - all read off {@link PairFacts}, which is itself built once
 * per request from the tenant's real rows.
 *
 * <p>Money/volume fields are primitive doubles, 0 when the underlying input is absent (no
 * demand signal, no stock count): a missing input contributes nothing to a sum, which is the
 * same behaviour as "skip this pair", so a caller summing across many pairs does not need a
 * null check per field. Callers that need to know whether a pair actually has stock or a
 * demand signal read {@link #hasInventory()}/{@link #hasDemand()} instead of inferring it from
 * a zero.
 */
final class SellEngine {

    private SellEngine() {
    }

    record SellSummary(
            String itemNumber,
            String storeId,
            String name,
            String storeLabel,
            double cost,
            double currentPrice,
            double recommended,
            double monthlyUnits,
            double annualUnits,
            double weeksOfCover,
            double inventoryValue,
            double upliftPerUnit,
            double upliftPct,
            double monthlyOpportunity,
            String demandLabel,
            boolean priceable,
            boolean hasInventory,
            boolean hasDemand) {
    }

    static SellSummary compute(PairFacts f) {
        String demandLabel = f.demand() == null ? "No demand signal on file" : f.demand().label();
        double currentPrice = Fmt.dv0(f.currentPrice());
        double recommended = Fmt.dv0(f.optimalPrice());
        double upliftPerUnit = Fmt.dv0(f.upliftPerUnit());
        double upliftPct = currentPrice > 0 ? Fmt.round1((upliftPerUnit / currentPrice) * 100) : 0;
        return new SellSummary(
                f.pair().itemNumber(), f.storeKey(), f.pair().shortName(), f.pair().storeLabel(),
                Fmt.dv0(f.cost()), currentPrice, recommended,
                Fmt.dv0(f.monthlyUnits()), Fmt.dv0(f.annualUnits()),
                f.hasInventory() ? Fmt.dv0(f.weeksOfCover()) : 0,
                f.hasInventory() ? Fmt.dv0(f.inventoryValue()) : 0,
                upliftPerUnit, upliftPct, Fmt.dv0(f.monthlyOpportunity()), demandLabel,
                f.priceable(), f.hasInventory(), f.demand() != null);
    }
}

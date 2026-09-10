package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import com.aatlas.insights.internal.PricingEngine.PricingModel;

/**
 * A port of the fields {@code intel/sell.ts}'s {@code getSellIntel} exposes that the
 * engines in this module actually read: the current and recommended price, the volume
 * and inventory position, and the extra monthly gross profit the recommendation would
 * add. Dropped: the {@code chain} of plain-language steps, the {@code timeline}, the
 * {@code nowVsWait} verdict and {@code elasticity} - all Sell-screen presentation, not
 * read by Overview, Insights, Stores or Products.
 *
 * <p>Every call site in this module only reaches {@code getSellIntel} for a pair the
 * pricing model already says is priceable (the frontend filters the same way before
 * calling it), so the TypeScript's early "not priceable" branch - an all-zero row - is
 * not reproduced; {@link #compute} assumes a priceable pair.
 */
final class SellEngine {

    private SellEngine() {
    }

    record Inventory(int units, double weeksOfCover) {
    }

    record SellSummary(
            String itemNumber,
            String storeId,
            String name,
            String storeLabel,
            double cost,
            double currentPrice,
            double recommended,
            int monthlyUnits,
            int annualUnits,
            double weeksOfCover,
            double inventoryValue,
            double upliftPerUnit,
            double upliftPct,
            double monthlyOpportunity,
            String demandLabel) {
    }

    /** Units this store moves of this item in a month. Cheap fittings move by the thousand. */
    static int monthlyUnitsFor(String itemNumber, String storeId, double cost) {
        String key = "vol:" + itemNumber + ":" + storeId;
        if (cost < 15) {
            return Seeded.randInt(key, "u", 700, 4200);
        }
        if (cost < 200) {
            return Seeded.randInt(key, "u", 90, 900);
        }
        return Seeded.randInt(key, "u", 4, 38);
    }

    static Inventory inventoryFor(String itemNumber, String storeId, int monthlyUnits) {
        String key = "inv:" + itemNumber + ":" + storeId;
        double weeksOfCover = Fmt.round1(Seeded.randRange(key, "cover", 2.2, 24));
        int units = Math.max(1, (int) Math.round((monthlyUnits / 4.33) * weeksOfCover));
        return new Inventory(units, weeksOfCover);
    }

    static SellSummary compute(String itemNumber, String storeId, CatalogSnapshot snapshot) {
        PricingModel m = PricingEngine.compute(itemNumber, storeId, snapshot);
        return compute(itemNumber, storeId, snapshot, m);
    }

    static SellSummary compute(String itemNumber, String storeId, CatalogSnapshot snapshot, PricingModel m) {
        ProductRef product = snapshot.product(itemNumber)
                .orElseThrow(() -> new IllegalStateException("Unknown item " + itemNumber));

        double recommended = m.optimalPrice();
        int monthlyUnits = monthlyUnitsFor(itemNumber, storeId, m.cost());
        int annualUnits = monthlyUnits * 12;
        Inventory inv = inventoryFor(itemNumber, storeId, monthlyUnits);
        double upliftPerUnit = Fmt.round2(recommended - m.currentPrice());
        double upliftPct = Fmt.round1((upliftPerUnit / m.currentPrice()) * 100);
        double monthlyOpportunity = Fmt.round2(upliftPerUnit * monthlyUnits);
        double inventoryValue = Fmt.round2(inv.units() * m.cost());
        String demandLabel = m.demand() == null ? "Stable demand" : m.demand().label();

        return new SellSummary(
                itemNumber, storeId, product.shortName(), GeoEngine.storeLabel(storeId, snapshot),
                m.cost(), m.currentPrice(), recommended, monthlyUnits, annualUnits,
                inv.weeksOfCover(), inventoryValue, upliftPerUnit, upliftPct, monthlyOpportunity, demandLabel);
    }
}

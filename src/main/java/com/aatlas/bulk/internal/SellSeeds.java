package com.aatlas.bulk.internal;

import com.aatlas.common.seed.Seeded;

/**
 * Small pure ports shared by {@link SellLineReaderImpl} and {@link OpportunityScoring}:
 * monthly volume, inventory position, and the sell-side elasticity coefficient. Exact
 * ports of {@code src/lib/intel/sell.ts}'s {@code monthlyUnitsFor}/{@code inventoryFor}
 * and {@code src/lib/platform/elasticity.ts}'s sell-side branch of
 * {@code getElasticityModel} (coefficient only - the curve, cross-item effects and
 * back-test rows it also computes are not read anywhere bulk needs, so this stops at the
 * one number the bulk strategies use).
 */
final class SellSeeds {

    private SellSeeds() {
    }

    static double monthlyUnitsFor(String itemNumber, String storeId, double cost) {
        String key = "vol:" + itemNumber + ":" + storeId;
        if (cost < 15) {
            return Seeded.randInt(key, "u", 700, 4200);
        }
        if (cost < 200) {
            return Seeded.randInt(key, "u", 90, 900);
        }
        return Seeded.randInt(key, "u", 4, 38);
    }

    record Inventory(double units, double weeksOfCover) {
    }

    static Inventory inventoryFor(String itemNumber, String storeId, double monthlyUnits) {
        String key = "inv:" + itemNumber + ":" + storeId;
        double weeksOfCover = PricingEngine.round1(Seeded.randRange(key, "cover", 2.2, 24));
        double units = Math.max(1, Math.round((monthlyUnits / 4.33) * weeksOfCover));
        return new Inventory(units, weeksOfCover);
    }

    /** {@code getElasticityModel(itemNumber, 'sell', storeId).coefficient}. */
    static double elasticity(String itemNumber, String storeId) {
        String key = "el:sell:" + itemNumber + ":" + (storeId == null || storeId.isBlank() ? "default" : storeId);
        return PricingEngine.round2(-1 * Seeded.randRange(key, "coef", 0.35, 2.6));
    }
}

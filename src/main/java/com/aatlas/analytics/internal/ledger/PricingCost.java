package com.aatlas.analytics.internal.ledger;

import com.aatlas.analytics.internal.fixtures.Fixtures;
import com.aatlas.common.seed.Seeded;

/**
 * The one slice of {@code mock/pricing.ts}'s {@code getPricingModel} the procurement ledger
 * needs: base cost and priceability. Everything else that pricing model computes (current
 * price, tiers, competitors, demand...) is the {@code sell}/{@code buy} tracks' concern - this
 * is deliberately not a full port, per the wave-2 brief's stand-in rule.
 *
 * <p>TODO(merge): retarget onto the real {@code getPricingModel} port (owned by {@code sell}
 * or a shared {@code engine} module) once one exists in this worktree. Cost is a pure
 * function of the item number alone (no store dependency), so this is a faithful subset, not
 * an approximation - the numbers will not change at merge.
 */
public final class PricingCost {

    private PricingCost() {
    }

    /**
     * Base cost for an item, spread across three tiers (fittings, mid-range, equipment).
     * Ported verbatim from {@code mock/pricing.ts}'s {@code baseCost}.
     */
    public static double baseCost(String item) {
        double r = Seeded.rand(item, "cost");
        long tier = Seeded.hashString(item) % 10;
        double v;
        if (tier <= 4) {
            v = 1.2 + r * 12;
        } else if (tier <= 8) {
            v = 18 + r * 120;
        } else {
            v = 320 + r * 900;
        }
        return Rounding.round2(v);
    }

    /** Ports {@code getPricingModel}'s {@code priceable} check for one (item, store) pair. */
    public static boolean priceable(String item, String storeId) {
        return Fixtures.priceable(item, storeId);
    }
}

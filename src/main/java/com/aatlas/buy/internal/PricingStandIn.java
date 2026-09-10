package com.aatlas.buy.internal;

import com.aatlas.common.seed.Seeded;
import org.springframework.stereotype.Component;

/**
 * The three fields of the frontend's {@code PricingModel} (from {@code mock/pricing.ts})
 * that the buy engines actually read: whether an (item, store) pair is priceable, the base
 * cost, and what the store charges today. Everything else that model carries (demand,
 * competitors, bands, the optimal/aggressive tiers) belongs to the sell/pricing engine,
 * which is a different wave-2 track (`sell`, worktree {@code api-wt-sell}) being built in
 * parallel and not visible from this worktree.
 *
 * <p>Per the stand-in rule, this is a small, straightforward, deterministic port of exactly
 * the subset {@code buy} needs - the same seeded arithmetic, reading the real product/branch
 * facts from {@link CatalogGateway} rather than re-seeding a parallel catalogue.
 *
 * <p>TODO(merge): replace with a call into {@code sell}'s public pricing reader once it
 * exists, and delete this class.
 */
@Component
class PricingStandIn {

    private final CatalogGateway catalog;

    PricingStandIn(CatalogGateway catalog) {
        this.catalog = catalog;
    }

    /** {@code priceable}, {@code cost} and {@code currentPrice} - see {@code PricingModel} in {@code mock/pricing.ts}. */
    record Model(boolean priceable, double cost, double currentPrice) {
    }

    /**
     * Base cost for an item, spread across three tiers (fittings, mid-range, equipment) by
     * the item's own hash - independent of store, exactly like the frontend's {@code baseCost}.
     */
    static double baseCost(String item) {
        double r = Seeded.rand(item, "cost");
        long tier = Seeded.hashString(item) % 10;
        if (tier <= 4) {
            return Js.round2(1.2 + r * 12);
        }
        if (tier <= 8) {
            return Js.round2(18 + r * 120);
        }
        return Js.round2(320 + r * 900);
    }

    Model modelFor(String itemNumber, String storeCode) {
        boolean hasProductWithSales = catalog.findProduct(itemNumber).map(CatalogGateway.ProductRow::hasSales)
                .orElse(false);
        boolean priceable = hasProductWithSales
                && (storeCode == null || storeCode.isBlank() || catalog.sells(itemNumber, storeCode));
        double cost = baseCost(itemNumber);
        String key = itemNumber + "|" + (storeCode == null ? "" : storeCode);
        double currentPrice = Js.round2(cost * Seeded.randRange(key, "current", 1.32, 2.15));
        return new Model(priceable, cost, currentPrice);
    }
}

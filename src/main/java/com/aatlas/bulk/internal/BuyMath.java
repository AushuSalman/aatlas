package com.aatlas.bulk.internal;

import com.aatlas.common.seed.Seeded;

/**
 * Exact port of {@code src/lib/intel/buy.ts}'s {@code annualVolumeFor} and
 * {@code regionLabel} - the two small pure functions {@code bulkBuyPlan} calls directly
 * rather than through {@code getBuyIntel}.
 */
final class BuyMath {

    private BuyMath() {
    }

    /** The volume a region buys of an item in a year - the base for every bulk/annual figure. */
    static double annualVolumeFor(String itemNumber, String regionKey, double cost) {
        String key = "avol:" + itemNumber + ":" + regionKey;
        if (cost < 15) {
            return Seeded.randInt(key, "v", 9000, 62000);
        }
        if (cost < 200) {
            return Seeded.randInt(key, "v", 1200, 11000);
        }
        return Seeded.randInt(key, "v", 60, 520);
    }

    /** US market regions, ported from {@code src/lib/platform/locale.ts}'s US entry. */
    static String regionLabel(String key) {
        return switch (key) {
            case "west" -> "West";
            case "north" -> "North";
            case "south" -> "South";
            case "east" -> "East";
            default -> key;
        };
    }
}

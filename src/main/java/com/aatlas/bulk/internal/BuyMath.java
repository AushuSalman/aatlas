package com.aatlas.bulk.internal;

/**
 * Pure, tenant-independent buy-side arithmetic bulk needs outside of
 * {@code buy.BuyIntelReader} itself - just the region label {@code bulkBuyPlan} stamps on
 * every plan and line. Every per-item cost/quote/volume figure now comes straight off
 * {@code buy.BuyIntel} (see {@link BuyLineReaderImpl}); nothing here reads a seed.
 */
final class BuyMath {

    private BuyMath() {
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

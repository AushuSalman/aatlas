package com.aatlas.sell.internal.catalog;

import java.util.List;

/**
 * The catalogue's fixed seed order - {@code PRODUCTS} in {@code src/lib/mock/catalog.ts}.
 *
 * <p>Every other read in {@link CatalogGateway} is order-independent (a lookup, or a set
 * this code re-sorts itself), but a handful of the ported engines call {@code
 * pick(key, salt, list)} - an index into the list, {@code list[floor(rand*n) % n]} - where
 * the list is the catalogue in exactly this order. Since {@code products.item_number} does
 * not sort alphabetically the same way, those picks need the real seed order, not a SQL
 * {@code ORDER BY item_number}.
 */
public final class SeedOrder {

    private SeedOrder() {
    }

    /** The 15 catalogue items, PIM-only ones (no sales) last, exactly as seeded. */
    public static final List<String> ITEM_NUMBERS = List.of(
            "HRD304148", "HRD118902", "HRD772310", "HRD450871", "HRD290145",
            "HRD661204", "HRD983377", "HRD512066", "HRD874019", "HRD335590",
            "HRD107744", "HRD248813", "HRD900001", "HRD900002", "HRD900003");
}

package com.aatlas.analytics.internal.ledger;

/**
 * Buying is organised by the product's own catalogue category (Plumbing, HVAC, Water
 * heating, Fixtures, ...) - the same {@code products.category} field the products import and
 * the pricing benchmarks read - not a separate hand-mapped scheme. A purchase-order line
 * whose category could not be resolved (no product on file, or the product carries none)
 * falls into {@link #UNCATEGORISED}, the same bucket {@code history.SalesHistory} uses for
 * the same case.
 */
public final class Categories {

    public static final String UNCATEGORISED = "uncategorised";

    private Categories() {
    }

    /** The category as shown: title case is already how {@code products.category} is stored. */
    public static String labelOf(String key) {
        if (key == null || key.isBlank() || UNCATEGORISED.equals(key)) {
            return "Uncategorised";
        }
        return key;
    }
}

package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the customer has decided an item sells for and costs: the current row of
 * {@code product_prices} per component, store-specific winning over tenant-wide.
 */
public interface PriceList {

    Optional<CurrentPrice> current(UUID productId, UUID storeIdOrNull, LocalDate today);

    /** Every product's current price at a store (or tenant-wide), in one query. */
    Map<UUID, CurrentPrice> currentForStore(UUID storeIdOrNull, LocalDate today);

    /**
     * The products with a current list price on file at any branch or tenant-wide: the
     * "priceable" half next to {@code has_sales}, one index scan rather than the ladder.
     */
    Set<UUID> pricedProducts(LocalDate today);

    PriceCoverage coverage(LocalDate today);

    /**
     * The current price and cost, each with its own provenance because they may come from
     * different rows (a wizard row sets only the list price; an import row may set only cost).
     *
     * @param listPrice null when no row carries one
     * @param listPriceSource {@code import}, {@code wizard}, {@code manual}, {@code applied} or {@code sample}
     * @param cost null when no row carries one
     */
    record CurrentPrice(
            BigDecimal listPrice, String listPriceSource, LocalDate listPriceFrom, boolean listPriceStoreSpecific,
            BigDecimal cost, String costSource, LocalDate costFrom, boolean costStoreSpecific,
            String basisJson) {
    }

    /** How much of the catalogue is priced, for readiness. */
    record PriceCoverage(int itemsWithPrice, int itemsWithCost, int quotesOnFile) {
    }
}

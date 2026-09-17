package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stock on hand ({@code inventory_positions}). */
public interface Inventory {

    /** Days after which a count is too old to price on; the section locks with the reason. */
    int STALE_AFTER_DAYS = 90;

    boolean hasInventory();

    Optional<OnHand> onHand(UUID productId, UUID storeIdOrNull);

    /** Every position, keyed by (product, store). */
    Map<PairKey, OnHand> all();

    Optional<LocalDate> latestAsOf();

    /** Coverage for readiness: distinct items, distinct stores, latest count date. */
    InventoryCoverage coverage();

    record OnHand(BigDecimal units, LocalDate asOf) {

        /** Whether the count is too old to price on, as of {@code today}. */
        public boolean stale(LocalDate today) {
            return asOf.plusDays(STALE_AFTER_DAYS).isBefore(today);
        }
    }

    record PairKey(UUID productId, UUID storeId) {
    }

    record InventoryCoverage(int items, int stores, LocalDate asOf) {
    }
}

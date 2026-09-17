package com.aatlas.history;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The truth ladder: where a current price, a cost and a market anchor come from, in order.
 *
 * <ul>
 *   <li>currentPrice: price list (store, else tenant-wide) → sales 90d at the store → sales
 *       12m at the store → sales 12m for the item anywhere (only when a store was asked for)
 *   <li>cost: purchases 90d (landed, any supplier) → price list → sales cost → supplier list
 *       (lowest ex-works on file plus its lane) → empty
 *   <li>anchor: competitor → peer → benchmark → history → empty
 * </ul>
 *
 * <p>Every field named cost or current price on every screen is resolved here, so two screens
 * can never disagree about the number or the label.
 */
public interface PriceLadder {

    Optional<Resolved> currentPrice(UUID productId, UUID storeIdOrNull, LocalDate today);

    Optional<Resolved> cost(UUID productId, UUID storeIdOrNull, LocalDate today);

    Optional<Anchor> anchor(UUID productId, UUID storeIdOrNull, LocalDate today);

    /** Every product's current price at a store, one query per rung. */
    Map<UUID, Resolved> currentPrices(UUID storeIdOrNull, LocalDate today);

    Map<UUID, Resolved> costs(UUID storeIdOrNull, LocalDate today);
}

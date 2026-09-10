package com.aatlas.buy;

/**
 * Public reader for {@link BuyIntel}.
 *
 * <p>{@code bulk}'s buy-side plan (a different track, a different worktree) needs a buy
 * intel/procurement-plan reader before this module's real code is visible to it, so it
 * builds its own stand-in for exactly this. Keeping {@link BuyIntel} and this interface at
 * the package root (rather than {@code buy.internal}) is what lets that stand-in be
 * retargeted here at merge time instead of guessing this module's future shape.
 */
public interface BuyIntelReader {

    /**
     * @param destinationId the branch to land the buy at; when null the busiest branch in
     *     {@code regionKey} is used ({@code primaryStoreForRegion})
     */
    BuyIntel getBuyIntel(String itemNumber, String regionKey, int qty, String destinationId);
}

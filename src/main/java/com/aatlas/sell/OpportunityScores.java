package com.aatlas.sell;

import java.util.List;

/**
 * The opportunity score: one number per product per store, port of {@code
 * src/lib/intel/score.ts}'s {@code opportunityScore}/{@code tierLabel}.
 *
 * <p>This module is the canonical owner of this port (per WAVE2-BRIEF). Other tracks -
 * Products, Overview - depend on it for the same chip Sell shows; they may carry their own
 * stand-in until this is wired in at merge time, which is expected.
 */
public interface OpportunityScores {

    /** The score for one (item, store); {@code tier: "risk"}/{@code score: 0} when not priceable. */
    OpportunityScoreView score(String itemNumber, String storeCode);

    /**
     * The best {@code limit} opportunities across every sellable product and every store in
     * a market region, highest score first. Computed on demand - there is no snapshot table
     * - so keep {@code limit} to what a screen actually renders.
     */
    List<OpportunityScoreView> rankRegion(String regionKey, int limit);
}

package com.aatlas.insights.internal;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The two places {@code overview.ts} reads data this module does not own, and cannot yet
 * reach: a small interface for exactly what is needed, and a straightforward stand-in
 * implementation, per the wave-2 brief's "stand-in now, retarget at merge" rule. Both
 * return the same "nothing yet" answer the golden fixture was captured against (an empty
 * browser state - no recorded decisions, no recorded deals).
 */
final class CrossTrackStandIns {

    private CrossTrackStandIns() {
    }

    /** One row of {@code intel/decisions.ts}'s {@code Decision} - only what Overview echoes back. */
    record DecisionView(String id, String kind, String itemNumber, String storeId, String summary, String at) {
    }

    /**
     * TODO(merge): replace with the {@code decisions} module's reader for what a user has
     * actually recorded ({@code intel/decisions.ts}'s {@code readDecisions()}). That module
     * lives in a different worktree (wave2-history / {@code api-wt-history}) and is not
     * reachable from here yet.
     */
    interface RecentDecisionsReader {
        List<DecisionView> recent();
    }

    /**
     * TODO(merge): replace with the {@code buy} module's procurement-ledger analytics
     * ({@code platform/procurement.ts}'s {@code computeBuyAnalytics} over
     * {@code PURCHASE_ORDERS}), which is what {@code platform/api.ts}'s
     * {@code summariseBuy()} actually reads for {@code impact.buy}. That engine is a
     * different track's large, independent ledger (794 purchase-order rows; a different
     * worktree, {@code wave2-buy} / {@code api-wt-buy}), not one of the two dependencies
     * named for this track, and out of scope to re-derive here. Two Overview KPIs read it:
     * "Procurement savings" ({@code impact.buy.gained}) and "Recommendation adoption"
     * (which blends {@code impact.sell} and {@code impact.buy}) - both are therefore
     * computed here from the sell side only until the real reader is wired in, and will not
     * match {@code golden/overview.json} for those two fields until it is (see this
     * module's README for the exact figures).
     */
    interface ProcurementImpactReader {
        int deals();

        int followedDeals();

        double gained();
    }

    @Component
    static final class NoRecentDecisions implements RecentDecisionsReader {
        @Override
        public List<DecisionView> recent() {
            return List.of();
        }
    }

    @Component
    static final class NoProcurementImpact implements ProcurementImpactReader {
        @Override
        public int deals() {
            return 0;
        }

        @Override
        public int followedDeals() {
            return 0;
        }

        @Override
        public double gained() {
            return 0;
        }
    }
}

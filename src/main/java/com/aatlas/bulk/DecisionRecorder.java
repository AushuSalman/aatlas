package com.aatlas.bulk;

import java.util.List;
import java.util.UUID;

/**
 * Records what applying a bulk strategy did: one decision, and one deal per basket line.
 *
 * <p><b>Stand-in.</b> {@code TODO(merge): replace with the decisions module's recorder.}
 * A bulk apply is a kind of decision like any other (a single-item price change, an RFQ
 * award) and belongs in that module's ledger; at the time this module was built,
 * {@code decisions} was still an empty placeholder in every worktree that could see it,
 * including {@code wave2-history}'s own branch, so this module owns
 * {@code bulk_decision}/{@code bulk_deal} (migration V13) and this narrow recorder only
 * until the merge retargets it.
 */
public interface DecisionRecorder {

    /** @return the new decision's id */
    UUID recordSellDecision(String storeCode, String strategyKey, double totalValue, double totalImpact,
            Object payload, List<DealLine> lines);

    /** @return the new decision's id */
    UUID recordBuyDecision(String regionKey, String strategyKey, double totalValue, double totalImpact,
            Object payload, List<DealLine> lines);

    /**
     * One applied basket line. {@code unitPrice} is set for a sell deal, {@code unitCost}
     * and the supplier fields for a buy deal; the other side is null.
     */
    record DealLine(
            String itemNumber,
            int qty,
            Double unitPrice,
            Double unitCost,
            String supplierKey,
            String supplierName) {
    }
}

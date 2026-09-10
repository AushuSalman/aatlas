package com.aatlas.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A real purchase order line to badge onto the ledger, awarded through a recorded buy
 * decision - the {@code decisions} module's write path when a user acts on a buy
 * recommendation (POST /buy/select or a buy-side apply, another track's endpoint, reached via
 * {@code decisions.DecisionRecorder}).
 *
 * <p>Ports the same idea as the frontend's RFQ award flow badging a purchase alongside the
 * seeded backdrop (see {@code intel/rfq.ts}'s {@code awardRfq} and {@code intel/decisions.ts}'s
 * {@code recordPurchase}): a real row lands in {@code purchase_order}, carrying
 * {@code decisionId} so the Buying insights ledger can show "Approved on Buy" next to it - the
 * exact copy is the frontend's job, this just keeps the link.
 *
 * <p>Costs are ex-works/freight/duty per unit, landed = exWorks + freight + duty. Category is
 * the same five-value scheme the ledger uses ({@code pipe}/{@code tube}/{@code valves}/
 * {@code equipment}/{@code fixtures}) - pass {@code null} to have it looked up from the item
 * number's usual category.
 */
public record RecordAward(
        String itemNumber,
        String description,
        String category,
        String supplierId,
        String supplierName,
        String country,
        String branchId,
        int qty,
        BigDecimal exWorks,
        BigDecimal freight,
        BigDecimal duty,
        BigDecimal baseline,
        BigDecimal target,
        LocalDate orderDate,
        UUID decisionId) {
}

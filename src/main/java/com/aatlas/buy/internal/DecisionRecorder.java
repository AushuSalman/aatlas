package com.aatlas.buy.internal;

import java.math.BigDecimal;

/**
 * Writes {@code buy}'s own audit row for what {@code POST /buy/select} decided - which
 * supplier was chosen, and whether the seat could commit it alone or the request went to
 * {@code approvals}. The real ledger write (a {@code decisions.Decision} plus a purchase
 * {@code DealRecord}) is a separate call, made by {@link BuyService} directly against {@code
 * com.aatlas.decisions.DecisionRecorder} - see its {@code select}/{@code onApprovalGranted}.
 *
 * <p>This table ({@code buy_decisions}, V10) exists so a pending award can be resolved later
 * ({@link #findByDecisionId}) the same way {@code rfq.RfqEntity} tracks its own
 * {@code decisionId} - the real decision is the source of truth, this row is buy's own index
 * onto it.
 */
interface DecisionRecorder {

    BuyDecisionEntity record(String itemNumber, String regionKey, String destinationStoreCode, String optionKey,
            String supplierId, Integer qty, BigDecimal orderValue, String status, String approverRole,
            BigDecimal approveLimit);

    java.util.Optional<BuyDecisionEntity> findByDecisionId(java.util.UUID decisionId);

    void save(BuyDecisionEntity entity);
}

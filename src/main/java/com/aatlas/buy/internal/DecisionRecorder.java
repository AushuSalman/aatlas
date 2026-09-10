package com.aatlas.buy.internal;

import java.math.BigDecimal;

/**
 * Writes what {@code POST /buy/select} decided: a decision and a purchase deal when the
 * seat's approval limit covers it, or an approval request when it does not.
 *
 * <p>This write genuinely belongs to the {@code decisions} module (Track D /
 * History-Analytics), which owns the decision/deal ledger the History screen reads - but
 * that module is being built in a different worktree in parallel and is not visible here.
 * Per the stand-in rule, {@code buy} carries its own minimal writer: a real, working record
 * (not a no-op), kept in its own table ({@code buy_decisions}, V10) rather than guessing the
 * shape of a table {@code decisions} has not published yet.
 *
 * <p>Approval itself is not a stand-in: whether the seat can commit alone is decided by
 * {@link com.aatlas.policy.Persona#canApprove(BigDecimal)}, the real, merged {@code policy}
 * module's public API.
 *
 * <p>TODO(merge): replace with a call into {@code decisions}' public writer (e.g. a
 * {@code DecisionWriter} in its package root) once it exists, and drop {@code buy_decisions}
 * (or repoint it as a read-side index onto that module's own table).
 */
interface DecisionRecorder {

    void record(String itemNumber, String regionKey, String destinationStoreCode, String optionKey,
            BigDecimal orderValue, String status, String approverRole, BigDecimal approveLimit);
}

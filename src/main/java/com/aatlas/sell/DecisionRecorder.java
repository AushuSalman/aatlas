package com.aatlas.sell;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Stand-in for the {@code decisions} module (Track D / History, a different worktree, not
 * merged onto this branch yet). {@code POST /sell/apply} and {@code POST /sell/quotes}
 * record a decision and a deal exactly the way {@code recordDecision} +
 * {@code recordSale}/{@code recordSale} do together in {@code
 * src/lib/intel/decisions.ts}/{@code src/lib/platform/recorded.ts}; {@code GET
 * /sell/outcomes} reads them back the way {@code getHistory} filters its rows for one
 * (item, store) line.
 *
 * <p>The implementation on this branch is deliberately trivial - it logs and keeps the
 * decisions in memory for the life of the process, returning a real id shaped like the
 * frontend's own ({@code dec-<epoch millis>-<0..999>}) - because the real tables ({@code
 * decisions}, {@code deals}) belong to the {@code decisions} module.
 *
 * <p>TODO(merge): replace with {@code com.aatlas.decisions}'s recorder once that module is
 * in this tree, retargeting {@link #record} and {@link #outcomesFor} to it.
 */
public interface DecisionRecorder {

    /** What one apply/quote writes: everything {@code recordDecision} + {@code recordSale} both need. */
    record RecordRequest(
            String kind,
            String itemNumber,
            String storeCode,
            String scope,
            BigDecimal recommended,
            BigDecimal applied,
            int qty,
            BigDecimal cost,
            BigDecimal baselinePrice,
            String title,
            String detail,
            BigDecimal expectedImpact,
            String impactLabel,
            String customerName) {
    }

    /** The frontend's {@code Decision} (decisions.ts) merged with the outcome {@code getHistory} computes. */
    record Recorded(
            String id,
            Instant at,
            String kind,
            String title,
            String itemNumber,
            String storeCode,
            String scope,
            BigDecimal recommended,
            BigDecimal applied,
            boolean followed,
            BigDecimal gain,
            BigDecimal lost,
            BigDecimal value,
            String outcome,
            String outcomeLabel,
            BigDecimal expectedImpact,
            String impactLabel,
            String detail,
            int qty,
            String customerName) {
    }

    Recorded record(RecordRequest request);

    /** Newest first, for one (item, store) line - what the Sell screen's Outcome tab shows. */
    List<Recorded> outcomesFor(String itemNumber, String storeCode);
}

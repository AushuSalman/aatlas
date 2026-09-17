package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes to the price list. The one way a list price or a cost is set: the wizard's bulk
 * apply, a manual edit, a Sell apply, and the products import all come through here.
 *
 * <p>Append-only: a write is new rows, never an update, so the history stays. Every row of
 * one call shares a {@code writeId} so the call can be undone while nothing newer has
 * superseded it.
 */
public interface PriceBook {

    /**
     * Writes one row per entry and returns the write id. Entries naming an unknown item or
     * store are reported in the result, not written.
     */
    WriteResult write(List<PriceWrite> rows, String source, UUID setBy, UUID importBatchIdOrNull);

    /** Deletes the rows of a write that no newer row for the same pair has superseded. */
    UndoResult undo(UUID writeId);

    /**
     * One row to write.
     *
     * @param storeCode null for tenant-wide
     * @param listPrice may be null when only a cost is set
     * @param cost may be null when only a list price is set
     * @param basis the explanation, stored verbatim; null when there is none
     */
    record PriceWrite(String itemNumber, String storeCode, BigDecimal listPrice, BigDecimal cost,
            LocalDate effectiveFrom, Map<String, Object> basis) {
    }

    record Skipped(String itemNumber, String storeCode, String reason) {
    }

    record WriteResult(UUID writeId, int written, List<Skipped> skipped) {
    }

    record UndoResult(int deleted, int kept) {
    }
}

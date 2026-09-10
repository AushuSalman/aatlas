package com.aatlas.analytics;

/**
 * The ledger's write path: recording a real awarded purchase order alongside the seeded
 * backdrop. See {@link RecordAward} for what a caller (today, {@code decisions}, on behalf of
 * another track's buy-side apply endpoint) supplies.
 */
public interface ProcurementLedger {

    PurchaseOrderRecord recordAward(RecordAward award);
}

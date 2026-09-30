package com.aatlas.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Books a sale someone recorded by hand into the sales history, so that every screen that
 * derives from {@code sales_transactions} - the Overview's revenue and margin, demand and
 * velocity, the price moves and regions on Insights - counts it the same way as an imported
 * invoice line.
 *
 * <p>Until this existed a recorded sale lived only in the decisions ledger: Decision history
 * and the adoption figure saw it, nothing else did, and a customer who records sales by hand
 * instead of uploading a file watched the Overview stay still.
 */
public interface SalesLedger {

    /**
     * Appends one line with {@code source = 'manual'} and marks the (item, branch) pair as
     * selling. Empty when the item is not in the catalogue, which is the only reason a
     * recorded sale cannot be booked; a missing branch or customer is booked without them.
     */
    Optional<UUID> record(RecordedSale sale);

    /**
     * @param storeCode the branch code the sale was made at, nullable
     * @param customerCode the customer's code in the catalogue, nullable; {@code customerName} is kept either way
     * @param reference what to file under invoice number: the deal key, so the line can be traced back
     */
    record RecordedSale(String itemNumber, String description, String storeCode, String customerCode,
            String customerName, LocalDate date, BigDecimal qty, BigDecimal unitPrice, BigDecimal unitCost,
            String reference) {
    }
}

package com.aatlas.analytics.internal.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * One purchase order line, landed at the branch that ordered it. Ported field for field from
 * the frontend's {@code PurchaseOrder} (platform/procurement.ts), plus the import trail.
 *
 * <p>The delivery fields are boxed: an imported line whose promised or received date the
 * file did not carry has {@code null} there, which means "not measurable" - never "late".
 *
 * @param seq build order (see {@code purchase_order.seq}): lets a read reproduce a stable sort
 *     when dates tie. Not on the frontend type - {@code @JsonIgnoreProperties} below keeps it
 *     off the wire.
 * @param poRef the PO number as the file wrote it (many lines share one); {@code id} stays
 *     unique per row
 * @param source {@code award}, {@code import} or {@code sample}
 */
@JsonIgnoreProperties({"seq"})
public record PoRow(
        int seq,
        String id,
        LocalDate date,
        String supplierId,
        String supplierName,
        String country,
        String itemNumber,
        String description,
        String category,
        String branchId,
        String branchName,
        String regionKey,
        String regionLabel,
        int qty,

        double exWorks,
        double freight,
        double duty,
        double landed,
        double baseline,
        double target,
        boolean followed,

        double spend,
        double baselineSpend,
        double saved,
        double leaked,

        String status,
        Integer promisedDays,
        Integer actualDays,
        Integer daysLate,
        Boolean onTime,
        LocalDate promisedDate,
        LocalDate receivedDate,
        String poRef,
        String source) {

    /** Received, and on or before the promise. False when late; false when not measurable. */
    public boolean wasOnTime() {
        return Boolean.TRUE.equals(onTime);
    }

    /** Received, and after the promise. False when on time; false when not measurable. */
    public boolean wasLate() {
        return Boolean.FALSE.equals(onTime);
    }
}

package com.aatlas.analytics.internal.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * One purchase order line, landed at the branch that ordered it. Ported field for field from
 * the frontend's {@code PurchaseOrder} (platform/procurement.ts).
 *
 * @param seq build order (see {@code purchase_order.seq}): lets a read reproduce
 *     {@code buildLedger()}'s exact stable sort when dates tie. Not on the frontend type -
 *     {@code @JsonIgnoreProperties} below keeps it off the wire so a serialised {@link PoRow}
 *     matches {@code PurchaseOrder} field for field.
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
        int promisedDays,
        int actualDays,
        int daysLate,
        boolean onTime,
        LocalDate promisedDate,
        LocalDate receivedDate) {
}

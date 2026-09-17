package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * One accepted purchase-order line.
 *
 * <p>{@code qty} is already rounded to a whole number (the ledger counts units); {@code
 * freight}, {@code duty} and {@code landedCost} are null when the file did not give them, and
 * the loader derives the missing ones. {@code promisedDate}/{@code receivedDate} are null when
 * blank, unreadable or impossible - the validator has already said so.
 */
public record PurchaseRow(
        int line,
        String item,
        String description,
        LocalDate orderDate,
        String supplier,
        String supplierCountry,
        int qty,
        BigDecimal unitCost,
        BigDecimal freight,
        BigDecimal duty,
        BigDecimal landedCost,
        String currency,
        String shipTo,
        String poNumber,
        LocalDate promisedDate,
        LocalDate receivedDate,
        Integer qtyReceived) implements AcceptedRow {

    @Override
    public Map<String, Object> preview() {
        Map<String, Object> out = new HashMap<>();
        out.put("item", item);
        out.put("description", description);
        out.put("orderDate", orderDate == null ? null : orderDate.toString());
        out.put("supplier", supplier);
        out.put("supplierCountry", supplierCountry);
        out.put("qty", qty);
        out.put("unitCost", unitCost);
        out.put("freight", freight);
        out.put("duty", duty);
        out.put("landedCost", landedCost);
        out.put("currency", currency);
        out.put("shipTo", shipTo);
        out.put("poNumber", poNumber);
        out.put("promisedDate", promisedDate == null ? null : promisedDate.toString());
        out.put("receivedDate", receivedDate == null ? null : receivedDate.toString());
        out.put("qtyReceived", qtyReceived);
        return out;
    }

    @Override
    public PurchaseRow forSample(int days, BigDecimal fx) {
        return new PurchaseRow(line, item, description, AcceptedRow.shift(orderDate, days), supplier,
                supplierCountry, qty, AcceptedRow.convert(unitCost, fx), AcceptedRow.convert(freight, fx),
                AcceptedRow.convert(duty, fx), AcceptedRow.convert(landedCost, fx), currency, shipTo, poNumber,
                AcceptedRow.shift(promisedDate, days), AcceptedRow.shift(receivedDate, days), qtyReceived);
    }
}

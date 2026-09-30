package com.aatlas.decisions.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/** Ports {@code intel/history.ts}'s {@code HistoryRow}. {@code id} is the underlying deal's frontend string id. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryRow(
        String id,
        LocalDate date,
        String side,
        String itemNumber,
        String name,
        String scope,
        int qty,
        double recommended,
        double applied,
        double actual,
        Double marginPct,
        boolean followed,
        String outcome,
        String outcomeLabel,
        double value,
        boolean recorded,
        String customer,
        /** What the decision did, once measured: "Price +5% · volume −12% · profit +6%"; absent until then. */
        String result,
        /** {@code worked}, {@code hurt}, {@code neutral} or {@code insufficient}; absent until measured. */
        String resultVerdict) {

    /** A row with no measured result yet. */
    public HistoryRow(String id, LocalDate date, String side, String itemNumber, String name, String scope, int qty,
            double recommended, double applied, double actual, Double marginPct, boolean followed, String outcome,
            String outcomeLabel, double value, boolean recorded, String customer) {
        this(id, date, side, itemNumber, name, scope, qty, recommended, applied, actual, marginPct, followed, outcome,
                outcomeLabel, value, recorded, customer, null, null);
    }
}

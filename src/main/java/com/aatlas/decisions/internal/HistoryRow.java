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
        double marginPct,
        boolean followed,
        String outcome,
        String outcomeLabel,
        double value,
        boolean recorded,
        String customer) {
}

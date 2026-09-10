package com.aatlas.analytics.internal.ledger;

/** A line furthest over target. Ports {@code procurement.ts}'s {@code Opportunity}. */
public record Opportunity(
        String key,
        String itemNumber,
        String description,
        String category,
        String supplierName,
        double leaked,
        int units,
        int orders,
        double gapPct,
        double avgLanded,
        double avgTarget) {
}

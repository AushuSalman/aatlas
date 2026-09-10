package com.aatlas.analytics.internal.ledger;

import java.util.List;

/** Ports {@code procurement.ts}'s {@code SupplierRow} - the Buying insights scorecard row. */
public record SupplierRow(
        String id,
        String name,
        String country,
        String vendorCode,
        double spend,
        double sharePct,
        int orders,
        int units,
        double saved,
        double leaked,
        double avgLanded,
        double priceIndex,
        double onTimePct,
        int avgLeadDays,
        int qualityPpm,
        int creditDays,
        String risk,
        List<Double> series,
        int colorIndex) {
}

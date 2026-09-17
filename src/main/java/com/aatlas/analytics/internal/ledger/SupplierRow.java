package com.aatlas.analytics.internal.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ports {@code procurement.ts}'s {@code SupplierRow} - the Buying insights scorecard row.
 *
 * <p>{@code priceIndex}, {@code avgLeadDays}, {@code qualityPpm} and {@code creditDays} are
 * boxed: null when the supplier panel does not carry the fact (see {@link SupplierFacts}) and
 * there is no window of received orders to observe it from either - "not assessed", never a
 * placeholder.
 */
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
        BigDecimal priceIndex,
        double onTimePct,
        Integer avgLeadDays,
        Integer qualityPpm,
        Integer creditDays,
        String risk,
        List<Double> series,
        int colorIndex) {
}

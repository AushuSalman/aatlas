package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;

/** One supplier's landed quote for an item into a destination. The frontend's {@code SupplierQuote}. */
@Schema(name = "SupplierQuote")
public record SupplierQuote(
        String supplierId,
        String name,
        String country,
        double exWorksCost,
        double freightCost,
        double dutyCost,
        double unitCost,
        int leadTimeDays,
        int transitDays,
        int totalLeadDays,
        double otifPct,
        boolean isCurrent,
        boolean isIncumbent) {
}

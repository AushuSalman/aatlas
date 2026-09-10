package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One of the options for this order (lowest cost, fastest, most reliable, balanced). */
@Schema(name = "ProcurementOption")
public record ProcurementOption(
        String key,
        String title,
        List<SupplierShare> suppliers,
        double unitCost,
        double situationalCost,
        int deliveryDays,
        int rangeLow,
        int rangeHigh,
        double onTimePct,
        double reliabilityPct,
        double missedPct,
        double totalCost,
        double vsCurrent,
        double vsCheapest,
        String risk,
        int riskScore,
        String bestFor,
        String cta,
        int score,
        boolean recommended,
        String reason) {

    public record SupplierShare(String id, String name, int sharePct, String route) {
    }
}

package com.aatlas.bulk.internal;

import com.aatlas.bulk.SupplierEval;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Wire shapes for {@code GET /buy/bulk/plan}, field-for-field ports of
 * {@code src/lib/intel/bulk.ts}'s {@code BulkBuyLine}, {@code Award}, {@code BuyProjection}
 * and {@code BulkBuyPlan}.
 */
final class BulkBuyDtos {

    private BulkBuyDtos() {
    }

    @Schema(name = "BulkBuyLine")
    record LineView(
            String itemNumber,
            String name,
            int qty,
            SupplierEval incumbent,
            List<SupplierEval> suppliers,
            double currentTotal) {
    }

    @Schema(name = "Award")
    record AwardView(String itemNumber, String supplierName, String supplierId, int sharePct, double unitCost) {
    }

    @Schema(name = "BuyProjection")
    record ProjectionView(
            String key,
            String title,
            String blurb,
            double totalCost,
            double savings,
            double savingsPct,
            String risk,
            double avgOtifPct,
            double avgLeadDays,
            @Schema(description = "Chance the basket lands on time, from the awarded suppliers' records.")
                    double fulfilmentPct,
            int supplierCount,
            String dependency,
            List<AwardView> awards,
            List<String> benefits) {
    }

    @Schema(name = "BulkBuyPlan")
    record PlanView(
            String regionKey,
            String regionLabel,
            List<LineView> lines,
            double totalUnits,
            double currentCost,
            double optimizedCost,
            double savings,
            List<ProjectionView> strategies,
            String recommendedKey) {
    }
}

package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for {@code GET /sell/bulk/plan}, field-for-field ports of
 * {@code src/lib/intel/bulk.ts}'s {@code BulkSellLine}, {@code SellProjection} and
 * {@code BulkSellPlan}.
 */
final class BulkSellDtos {

    private BulkSellDtos() {
    }

    @Schema(name = "BulkSellLine")
    record LineView(
            String itemNumber,
            String name,
            double cost,
            double current,
            double recommended,
            double inventoryUnits,
            double inventoryValue,
            double weeksOfCover,
            @Schema(description = "(recommended - current) x inventory: what repricing the stock on hand is "
                    + "worth. Null when there is no stock count to price it on (see locked).")
                    Double opportunity,
            int score,
            String tier,
            SellLine intel,
            @Schema(description = "cost/currentPrice/anchor/units/inventory, straight off sell.SellLines.")
                    Map<String, String> sources,
            @Schema(description = "Section keys this line's plan should hide, e.g. inventory.")
                    List<String> locked,
            @Schema(description = "The stock count's as-of date; null with no count on file.")
                    LocalDate inventoryAsOf) {
    }

    @Schema(name = "SellProjection")
    record ProjectionView(
            String key,
            String title,
            String blurb,
            Map<String, Double> prices,
            double unitsSold,
            double revenue,
            double profit,
            double marginPct,
            @Schema(description = "Null when no line in the plan has a stock count to turn over.")
                    Double turnoverPct,
            String risk) {
    }

    @Schema(name = "BulkSellPlan")
    record PlanView(
            String storeId,
            String storeLabel,
            List<LineView> lines,
            double totalInventoryValue,
            double totalOpportunity,
            ProjectionView current,
            List<ProjectionView> strategies,
            String recommendedKey) {
    }
}

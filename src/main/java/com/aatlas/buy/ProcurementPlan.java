package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The procurement decision for one order: options A-D, ranked suppliers, trade-offs, a
 * decision score and a cost/speed matrix. The frontend's {@code ProcurementPlan} in
 * {@code intel/buy2.ts}, built by {@code procurementPlan}.
 */
@Schema(name = "ProcurementPlan")
public record ProcurementPlan(
        int requiredDays,
        String urgency,
        String priority,
        Weights weights,
        List<ScoredSupplier> ranked,
        List<ProcurementOption> options,
        ProcurementOption recommended,
        String reason,
        // Genuinely nullable in the frontend (`let notInAHurry = null;`, present in the
        // returned object either way) rather than merely optional-and-unset, so - like
        // ChainStep.effect - it needs @JsonInclude(ALWAYS) to override the application's
        // default non_null inclusion and serialise as "notInAHurry":null rather than being
        // dropped.
        @JsonInclude(JsonInclude.Include.ALWAYS) NotInAHurry notInAHurry,
        List<Tradeoff> tradeoffs,
        DecisionScore decisionScore,
        List<MatrixRow> matrix) {

    /** With a long window, the slow reliable supplier is often the cheap one. Null under 21 days. */
    public record NotInAHurry(
            String supplierName,
            double unitCost,
            int deliveryDays,
            double reliabilityPct,
            double savings,
            int bufferDays,
            String text) {
    }

    public record MatrixRow(String id, String name, int days, double cost, String risk, boolean recommended) {
    }
}

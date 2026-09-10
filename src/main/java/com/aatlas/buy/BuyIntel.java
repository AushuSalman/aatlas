package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Buy-side intelligence for one (item, region, quantity): effective cost per supplier,
 * now-vs-wait, and the negotiation letter. The frontend's {@code BuyIntel} in
 * {@code intel/buy.ts}, built by {@code getBuyIntel}.
 *
 * <p>{@code finalStep} carries the JSON name {@code final} - the frontend's own field name -
 * because {@code final} is a reserved word in Java.
 */
@Schema(name = "BuyIntel")
public record BuyIntel(
        String itemNumber,
        String name,
        String description,
        boolean priceable,
        String regionKey,
        String regionLabel,
        String destinationId,
        String destinationLabel,
        int qty,

        double currentCost,
        double targetCost,
        double supplierAverage,
        double marketBenchmark,
        double bestObserved,
        double sellPrice,
        double marginNowPct,
        double savingPerUnit,
        double savingPct,
        double potentialSavings,
        int confidence,
        String confidenceLabel,

        List<SupplierEval> suppliers,
        SupplierEval incumbent,
        SupplierEval recommendedSupplier,
        SupplierEval cheapestQuoted,
        String reason,

        List<ChainStep> chain,
        @JsonProperty("final") ChainStep finalStep,

        BuyNowVsWait nowVsWait,
        Negotiation negotiation,
        int annualVolume) {
}

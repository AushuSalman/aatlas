package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Buy-side intelligence for one (item, region, quantity): effective cost per supplier,
 * now-vs-wait, and the negotiation letter. The frontend's {@code BuyIntel} in
 * {@code intel/buy.ts}, built by {@code getBuyIntel}.
 *
 * <p>{@code finalStep} carries the JSON name {@code final} - the frontend's own field name -
 * because {@code final} is a reserved word in Java.
 *
 * <p>{@link #sources()} labels each headline figure's provenance; {@link #locked()} lists
 * sections this tenant's data cannot fill in yet (typically {@code purchases} - no purchase
 * history for this item).
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

        BigDecimal currentCost,
        BigDecimal targetCost,
        BigDecimal supplierAverage,
        BigDecimal marketBenchmark,
        BigDecimal bestObserved,
        BigDecimal sellPrice,
        BigDecimal marginNowPct,
        BigDecimal savingPerUnit,
        BigDecimal savingPct,
        BigDecimal potentialSavings,
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
        Integer annualVolume,
        Map<String, String> sources,
        List<String> locked) {
}

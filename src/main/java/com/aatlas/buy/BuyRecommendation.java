package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The landed-cost panel for one (item, destination, supplier): ex-works plus freight plus
 * duty on each supplier's own lane, ranked landed at the chosen branch. The frontend's
 * {@code BuyRecommendation} in {@code platform/types.ts}, built by {@code buildBuyRecommendation}
 * in {@code platform/api.ts}.
 */
@Schema(name = "BuyRecommendation")
public record BuyRecommendation(
        String itemNumber,
        String description,
        String supplierId,
        String supplierName,
        boolean priceable,
        String destinationId,
        String destinationName,
        String regionLabel,
        Lane lane,
        String incumbentSupplierId,
        String incumbentSupplierName,
        double incumbentCost,
        boolean isOverride,
        double sellPrice,
        boolean sellPriceLocal,
        double marginNowPct,
        double marginAtTargetPct,
        double marginGainPts,
        double grossNow,
        double grossAtTarget,
        double currentExWorks,
        double currentFreight,
        double currentDuty,
        double currentCost,
        double targetCost,
        double savingPerUnit,
        double savingPct,
        int annualUnits,
        double annualSaving,
        List<SupplierQuote> quotes,
        double marketLow,
        double marketMedian,
        double marketHigh,
        List<CalcStep> steps,
        List<FactorWeight> weights,
        double floorCost) {
}

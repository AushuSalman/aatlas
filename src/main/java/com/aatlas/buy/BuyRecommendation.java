package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The landed-cost panel for one (item, destination, supplier): ex-works plus freight plus
 * duty on each supplier's own lane, ranked landed at the chosen branch. The frontend's
 * {@code BuyRecommendation} in {@code platform/types.ts}.
 *
 * <p>Every money/percentage field that can genuinely be absent (no purchase history, no
 * quotes, no sell price on file) is null rather than a placeholder; the section key is then
 * in {@link #locked()}. {@link #sources()} labels where each figure came from
 * ({@code observed-90d}, {@code observed-12m}, {@code lane-estimate}, {@code purchases},
 * {@code sales}, ladder labels for cost/currentPrice) so the UI can badge it.
 *
 * @param incumbentReason set only when {@link #incumbentSupplierId()} is null: why there is
 *     nobody to compare against yet (no purchase history and no supplier quotes on file)
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
        String incumbentReason,
        BigDecimal incumbentCost,
        boolean isOverride,
        BigDecimal sellPrice,
        boolean sellPriceLocal,
        BigDecimal marginNowPct,
        BigDecimal marginAtTargetPct,
        BigDecimal marginGainPts,
        BigDecimal grossNow,
        BigDecimal grossAtTarget,
        BigDecimal currentExWorks,
        BigDecimal currentFreight,
        BigDecimal currentDuty,
        BigDecimal currentCost,
        BigDecimal targetCost,
        BigDecimal savingPerUnit,
        BigDecimal savingPct,
        Integer annualUnits,
        BigDecimal annualSaving,
        List<SupplierQuote> quotes,
        BigDecimal marketLow,
        BigDecimal marketMedian,
        BigDecimal marketHigh,
        List<CalcStep> steps,
        List<FactorWeight> weights,
        BigDecimal floorCost,
        Map<String, String> sources,
        List<String> locked) {
}

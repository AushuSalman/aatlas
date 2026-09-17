package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * One supplier, evaluated: the landed quote plus everything a buyer weighs on top of it -
 * reliability, lead time, quality, fulfilment, payment terms and minimum order - folded into
 * one effective cost. The frontend's {@code SupplierEval} in {@code intel/buy.ts}.
 *
 * <p>{@code quoted}/{@code landed}/{@code effective} are null when this supplier has no quote
 * on file for the item: it is still listed (lead time and OTIF let it be compared and scored
 * on delivery alone), but it is never awarded and never carries a fabricated cost.
 */
@Schema(name = "SupplierEval")
public record SupplierEval(
        String supplierId,
        String name,
        String country,
        BigDecimal quoted,
        BigDecimal landed,
        BigDecimal effective,
        BigDecimal freightAndDuty,
        Integer leadDays,
        BigDecimal otifPct,
        BigDecimal defectPct,
        BigDecimal fulfilmentPct,
        String terms,
        CommercialTerms commercial,
        BigDecimal penaltyRecoveryPerUnit,
        Integer moq,
        Boolean meetsMoq,
        Integer relationshipYears,
        List<Adjustment> adjustments,
        boolean isIncumbent,
        boolean recommended,
        String risk,
        String riskNote,
        boolean hasQuote,
        Boolean holdsStock) {

    /** One hidden cost, per unit, so the gap between quoted and effective can be itemised. */
    public record Adjustment(String label, BigDecimal amount) {
    }
}

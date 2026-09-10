package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One supplier, evaluated: the landed quote plus everything a buyer weighs on top of it -
 * reliability, lead time, quality, fulfilment, payment terms and minimum order - folded into
 * one effective cost. The frontend's {@code SupplierEval} in {@code intel/buy.ts}.
 */
@Schema(name = "SupplierEval")
public record SupplierEval(
        String supplierId,
        String name,
        String country,
        double quoted,
        double landed,
        double effective,
        double freightAndDuty,
        int leadDays,
        double otifPct,
        double defectPct,
        double fulfilmentPct,
        String terms,
        CommercialTerms commercial,
        double penaltyRecoveryPerUnit,
        int moq,
        boolean meetsMoq,
        int relationshipYears,
        List<Adjustment> adjustments,
        boolean isIncumbent,
        boolean recommended,
        String risk,
        String riskNote) {

    /** One hidden cost, per unit, so the gap between quoted and effective can be itemised. */
    public record Adjustment(String label, double amount) {
    }
}

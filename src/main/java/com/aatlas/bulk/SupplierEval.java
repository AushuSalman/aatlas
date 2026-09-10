package com.aatlas.bulk;

import java.util.List;

/**
 * One supplier's quote, evaluated into an all-in effective cost. Field-for-field port of
 * the frontend's {@code SupplierEval} (src/lib/intel/buy.ts's {@code evaluate}).
 *
 * <p><b>Stand-in.</b> {@code TODO(merge): replace with the buy module's public
 * SupplierEval reader}. The real {@code evaluate} adjusts a {@code SupplierQuote} that
 * {@code buildBuyRecommendation} (src/lib/platform/api.ts) derives from a full lane model
 * - freight mode, gateway, inbound/duty percent by country, inland percent and days by
 * region. This module has no lane model, so {@link BuyLineReader}'s implementation
 * synthesises {@code quoted}/{@code freightAndDuty} itself from the supplier's seeded
 * country and price index (see its class doc); every field from here down - the
 * reliability/lead-time/quality/fulfilment/commercial adjustments that turn a landed cost
 * into an effective one - is the real {@code evaluate()} arithmetic, unchanged.
 *
 * @param quoted ex-works cost, before freight and duty
 * @param landed quoted plus freight and duty
 * @param effective landed plus every hidden cost adjustment below
 * @param adjustments each hidden cost, per unit: {@code {label, amount}}
 * @param risk {@code Low}, {@code Medium} or {@code High}
 */
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

    public record Adjustment(String label, double amount) {
    }
}

package com.aatlas.bulk;

/**
 * A supplier's paperwork, priced. Field-for-field port of the frontend's
 * {@code CommercialTerms} (src/lib/intel/terms.ts), read straight from
 * {@code seed/suppliers.json}'s {@code terms} object rather than re-derived from a hash -
 * the seed file already carries the exact values the TypeScript would compute.
 */
public record CommercialTerms(
        int creditDays,
        String termsLabel,
        double earlyPayDiscountPct,
        int earlyPayDays,
        double latePenaltyPctPerWeek,
        double latePenaltyCapPct,
        int warrantyMonths,
        int quoteValidityDays,
        String incoterm,
        double invoiceAccuracyPct,
        int capacityUnitsMonth) {
}

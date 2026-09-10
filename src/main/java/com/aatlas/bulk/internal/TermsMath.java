package com.aatlas.bulk.internal;

import com.aatlas.bulk.CommercialTerms;

/**
 * What a supplier's paperwork is worth, per unit. Exact port of
 * {@code src/lib/intel/terms.ts}'s pricing functions (not the terms themselves - those are
 * read straight from {@code seed/suppliers.json}, see {@link BulkSeedCatalog}).
 */
final class TermsMath {

    /** Annual cost of capital used to value credit, percent. Stated, not modelled. */
    private static final double COST_OF_CAPITAL_PCT = 8;

    private TermsMath() {
    }

    static double creditValuePerUnit(double unitCost, int creditDays) {
        return PricingEngine.round2(unitCost * (creditDays / 365.0) * (COST_OF_CAPITAL_PCT / 100));
    }

    static double earlyPayNetPerUnit(double unitCost, CommercialTerms t) {
        if (t.earlyPayDiscountPct() <= 0) {
            return 0;
        }
        double discount = unitCost * (t.earlyPayDiscountPct() / 100);
        double creditGivenUp = creditValuePerUnit(unitCost, Math.max(0, t.creditDays() - t.earlyPayDays()));
        return PricingEngine.round2(Math.max(0, discount - creditGivenUp));
    }

    static double penaltyRecoveryCapPerUnit(double unitCost, CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() <= 0) {
            return 0;
        }
        double typicalSlipWeeks = 1.5;
        return PricingEngine.round2(
                unitCost * Math.min(t.latePenaltyCapPct(), t.latePenaltyPctPerWeek() * typicalSlipWeeks) / 100);
    }
}

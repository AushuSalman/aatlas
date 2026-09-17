package com.aatlas.suppliers.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * What a supplier's terms are worth per unit, and the labels the screens render for them.
 *
 * <p>Rewritten from a port of the frontend's {@code intel/terms.ts}: that version seeded
 * every term deterministically from the supplier's key so every screen agreed, which also
 * meant a supplier the platform has never traded with was shown a fully-formed set of
 * commercial terms nobody had ever agreed to. This version never invents one. A term a
 * person or a file has not supplied is {@code null} - "not provided" - and every label
 * function returns {@code null} for it rather than a default that reads as a fact.
 */
final class TermsScoring {

    private TermsScoring() {
    }

    /** Annual cost of capital used to value credit, percent. Stated, not modelled. */
    static final int COST_OF_CAPITAL_PCT = 8;

    /** A supplier with nothing on file yet: every term absent. */
    static CommercialTerms unspecified() {
        return CommercialTerms.UNSPECIFIED;
    }

    // -- What the terms are worth, per unit -----------------------------------------------------

    /** Credit is money: the cost of capital on the price for the days you hold it. Null without credit terms. */
    static Double creditValuePerUnit(double unitCost, Integer creditDays) {
        if (creditDays == null) {
            return null;
        }
        return Js.round2(unitCost * (creditDays / 365.0) * (COST_OF_CAPITAL_PCT / 100.0));
    }

    /**
     * An early-settlement discount, net of the credit you give up to take it. Null when the
     * terms are not on file; zero when a discount is on file but not offered or not worth it.
     */
    static Double earlyPayNetPerUnit(double unitCost, CommercialTerms t) {
        if (t.earlyPayDiscountPct() == null || t.creditDays() == null) {
            return null;
        }
        if (t.earlyPayDiscountPct() <= 0) {
            return 0.0;
        }
        int earlyPayDays = t.earlyPayDays() == null ? 0 : t.earlyPayDays();
        double discount = unitCost * (t.earlyPayDiscountPct() / 100.0);
        Double creditGivenUp = creditValuePerUnit(unitCost, Math.max(0, t.creditDays() - earlyPayDays));
        return Js.round2(Math.max(0, discount - (creditGivenUp == null ? 0 : creditGivenUp)));
    }

    /**
     * The most a late clause recovers per unit: the weekly rate over a typical slip of about
     * ten days, capped where the contract caps it. Null without terms on file; zero without a clause.
     */
    static Double penaltyRecoveryCapPerUnit(double unitCost, CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() == null) {
            return null;
        }
        if (t.latePenaltyPctPerWeek() <= 0) {
            return 0.0;
        }
        double cap = t.latePenaltyCapPct() == null ? t.latePenaltyPctPerWeek() : t.latePenaltyCapPct();
        double typicalSlipWeeks = 1.5;
        return Js.round2(unitCost * Math.min(cap, t.latePenaltyPctPerWeek() * typicalSlipWeeks) / 100.0);
    }

    /** Null when no late-delivery terms are on file. */
    static String latePenaltyLabel(CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() == null) {
            return null;
        }
        if (t.latePenaltyPctPerWeek() <= 0) {
            return "No late-delivery clause";
        }
        return Js.num(t.latePenaltyPctPerWeek()) + "% a week late, capped at "
                + (t.latePenaltyCapPct() == null ? "?" : Js.num(t.latePenaltyCapPct())) + "%";
    }

    /** Null when no credit terms are on file. */
    static String creditLabel(CommercialTerms t) {
        if (t.creditDays() == null) {
            return null;
        }
        return t.creditDays() == 0 ? "None, pay up front" : t.creditDays() + " days";
    }

    /** Null when no credit terms are on file at all; "None" when terms exist but no discount is offered. */
    static String earlyPayLabel(CommercialTerms t) {
        if (t.creditDays() == null) {
            return null;
        }
        return t.earlyPayDiscountPct() != null && t.earlyPayDiscountPct() > 0
                ? Js.num(t.earlyPayDiscountPct()) + "% if paid in " + t.earlyPayDays() + " days"
                : "None";
    }

    /** The things a buyer would flag before awarding, over whichever fields are on file. Empty when nothing stands out. */
    static List<String> termsWatchOuts(CommercialTerms t, int orderQty) {
        List<String> out = new ArrayList<>();
        if (t.creditDays() != null && t.creditDays() == 0) {
            out.add("Wants payment up front.");
        }
        if (t.latePenaltyPctPerWeek() != null && t.latePenaltyPctPerWeek() <= 0) {
            out.add("No late-delivery clause: a missed date costs them nothing.");
        }
        if (t.capacityUnitsMonth() != null && orderQty > t.capacityUnitsMonth()) {
            out.add("This order is above their " + Js.localeInt(t.capacityUnitsMonth()) + " units a month capacity.");
        }
        if (t.invoiceAccuracyPct() != null && t.invoiceAccuracyPct() < 90) {
            out.add("Invoices are right " + Js.toFixed(t.invoiceAccuracyPct(), 1) + "% of the time.");
        }
        return out;
    }
}

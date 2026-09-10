package com.aatlas.suppliers.internal;

import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a supplier's paperwork says, and what it is worth per unit. A port of the frontend's
 * {@code intel/terms.ts}, the one place terms are seeded and priced so the Buy screen, the
 * supplier row and the Suppliers page all read the same numbers.
 *
 * <p>Seeded deterministically from {@code sup:<supplierId>}, the same key namespace the
 * rating engine uses - a supplier added through the lookup gets terms the same way, as if
 * they had been pulled with the rest of its profile.
 */
final class TermsScoring {

    private TermsScoring() {
    }

    /** Annual cost of capital used to value credit, percent. Stated, not modelled. */
    static final int COST_OF_CAPITAL_PCT = 8;

    private static final List<String> INCOTERMS_DOMESTIC = List.of("FOB Destination", "FOB Origin", "DAP");
    private static final List<String> INCOTERMS_IMPORT = List.of("FOB Origin Port", "CIF", "EXW", "DDP");

    private static boolean isDomestic(String country) {
        String c = country == null ? "" : country.strip().toLowerCase(Locale.ROOT);
        return "usa".equals(c) || "us".equals(c) || "united states".equals(c);
    }

    static CommercialTerms commercialTerms(String supplierId, String country) {
        String k = "sup:" + supplierId;
        boolean domestic = isDomestic(country);
        int creditDays = Seeded.pick(k, "ct-credit",
                domestic ? List.of(30, 45, 60) : List.of(0, 15, 30, 45));
        double earlyPayDiscountPct = Seeded.rand(k, "ct-epd") > 0.5
                ? Math.round(Seeded.randRange(k, "ct-epdv", 1, 2.5) * 10) / 10.0
                : 0;
        int earlyPayDays = earlyPayDiscountPct > 0 ? Seeded.pick(k, "ct-epdays", List.of(7, 10, 15)) : 0;
        double latePenaltyPctPerWeek = Seeded.rand(k, "ct-pen8") > 0.35
                ? Js.round2(Seeded.randRange(k, "ct-penv", 0.5, 2))
                : 0;
        String termsLabel = CommercialTerms.labelFor(creditDays, earlyPayDiscountPct, earlyPayDays);
        double latePenaltyCapPct = latePenaltyPctPerWeek > 0
                ? Seeded.pick(k, "ct-pencap", List.of(3, 5, 5, 10))
                : 0;
        int warrantyMonths = Seeded.pick(k, "ct-warr", List.of(12, 12, 18, 24, 36));
        int quoteValidityDays = Seeded.pick(k, "ct-valid", List.of(14, 21, 30, 45, 60));
        String incoterm = Seeded.pick(k, "ct-inco", domestic ? INCOTERMS_DOMESTIC : INCOTERMS_IMPORT);
        double invoiceAccuracyPct = Js.round2(Seeded.randRange(k, "ct-inv", 88, 99.8));
        int capacityUnitsMonth = Seeded.randInt(k, "ct-cap", 400, 26000);

        return new CommercialTerms(creditDays, termsLabel, earlyPayDiscountPct, earlyPayDays,
                latePenaltyPctPerWeek, latePenaltyCapPct, warrantyMonths, quoteValidityDays, incoterm,
                invoiceAccuracyPct, capacityUnitsMonth);
    }

    // -- What the terms are worth, per unit -----------------------------------------------------

    /** Credit is money: the cost of capital on the price for the days you hold it. */
    static double creditValuePerUnit(double unitCost, int creditDays) {
        return Js.round2(unitCost * (creditDays / 365.0) * (COST_OF_CAPITAL_PCT / 100.0));
    }

    /**
     * An early-settlement discount, net of the credit you give up to take it. Zero when no
     * discount is offered or it is not worth it.
     */
    static double earlyPayNetPerUnit(double unitCost, CommercialTerms t) {
        if (t.earlyPayDiscountPct() <= 0) {
            return 0;
        }
        double discount = unitCost * (t.earlyPayDiscountPct() / 100.0);
        double creditGivenUp = creditValuePerUnit(unitCost, Math.max(0, t.creditDays() - t.earlyPayDays()));
        return Js.round2(Math.max(0, discount - creditGivenUp));
    }

    /**
     * The most a late clause recovers per unit: the weekly rate over a typical slip of about
     * ten days, capped where the contract caps it. Zero without a clause.
     */
    static double penaltyRecoveryCapPerUnit(double unitCost, CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() <= 0) {
            return 0;
        }
        double typicalSlipWeeks = 1.5;
        return Js.round2(unitCost * Math.min(t.latePenaltyCapPct(), t.latePenaltyPctPerWeek() * typicalSlipWeeks)
                / 100.0);
    }

    static String latePenaltyLabel(CommercialTerms t) {
        if (t.latePenaltyPctPerWeek() <= 0) {
            return "No late-delivery clause";
        }
        return Js.num(t.latePenaltyPctPerWeek()) + "% a week late, capped at " + Js.num(t.latePenaltyCapPct()) + "%";
    }

    static String creditLabel(CommercialTerms t) {
        return t.creditDays() == 0 ? "None, pay up front" : t.creditDays() + " days";
    }

    static String earlyPayLabel(CommercialTerms t) {
        return t.earlyPayDiscountPct() > 0
                ? Js.num(t.earlyPayDiscountPct()) + "% if paid in " + t.earlyPayDays() + " days"
                : "None";
    }

    /** The things a buyer would flag before awarding, stated plainly. Empty when nothing stands out. */
    static List<String> termsWatchOuts(CommercialTerms t, int orderQty) {
        List<String> out = new ArrayList<>();
        if (t.creditDays() == 0) {
            out.add("Wants payment up front.");
        }
        if (t.latePenaltyPctPerWeek() <= 0) {
            out.add("No late-delivery clause: a missed date costs them nothing.");
        }
        if (orderQty > t.capacityUnitsMonth()) {
            out.add("This order is above their " + Js.localeInt(t.capacityUnitsMonth()) + " units a month capacity.");
        }
        if (t.invoiceAccuracyPct() < 90) {
            out.add("Invoices are right " + Js.toFixed(t.invoiceAccuracyPct(), 1) + "% of the time.");
        }
        return out;
    }
}

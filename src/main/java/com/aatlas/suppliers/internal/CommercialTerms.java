package com.aatlas.suppliers.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a supplier's paperwork says. Mirrors {@code CommercialTerms} in the frontend's
 * {@code intel/terms.ts}, which is the one place these are seeded and priced so the Buy
 * screen, the supplier row and the Suppliers page read the same terms.
 *
 * <p>Every field is null when it was never supplied: a supplier added by name and country
 * carries no terms until a person or a file states them. {@link TermsScoring} never invents
 * one.
 *
 * @param creditDays days of credit; 0 means payment against proforma, a real cash cost
 * @param termsLabel as terms are written: "Net 45", "2/10 net 30", "Proforma"
 * @param earlyPayDiscountPct early-settlement discount, percent; 0 when none is offered
 * @param latePenaltyPctPerWeek liquidated damages, percent of order value per week late; 0 means no clause
 * @param latePenaltyCapPct the cap on that clause - the number that says whether it has teeth
 * @param capacityUnitsMonth units a month this supplier can take before the line becomes a promise
 */
@Schema(name = "CommercialTerms")
@JsonIgnoreProperties(ignoreUnknown = true)
record CommercialTerms(
        Integer creditDays,
        String termsLabel,
        Double earlyPayDiscountPct,
        Integer earlyPayDays,
        Double latePenaltyPctPerWeek,
        Double latePenaltyCapPct,
        Integer warrantyMonths,
        Integer quoteValidityDays,
        String incoterm,
        Double invoiceAccuracyPct,
        Integer capacityUnitsMonth) {

    /** Nothing supplied: every field absent. */
    static final CommercialTerms UNSPECIFIED =
            new CommercialTerms(null, null, null, null, null, null, null, null, null, null, null);

    /** The label the numbers imply. Recomputed whenever a term is edited. Null when no credit terms are on file. */
    static String labelFor(Integer creditDays, Double earlyPayDiscountPct, Integer earlyPayDays) {
        if (creditDays == null) {
            return null;
        }
        if (creditDays == 0) {
            return "Proforma";
        }
        return earlyPayDiscountPct != null && earlyPayDiscountPct > 0
                ? Js.num(earlyPayDiscountPct) + "/" + earlyPayDays + " net " + creditDays
                : "Net " + creditDays;
    }
}

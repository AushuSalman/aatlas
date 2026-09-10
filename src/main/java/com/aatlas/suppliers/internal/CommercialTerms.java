package com.aatlas.suppliers.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a supplier's paperwork says. Mirrors {@code CommercialTerms} in the frontend's
 * {@code intel/terms.ts}, which is the one place these are seeded and priced so the Buy
 * screen, the supplier row and the Suppliers page read the same terms.
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

    /** The label the numbers imply. Recomputed whenever a term is edited. */
    static String labelFor(int creditDays, double earlyPayDiscountPct, int earlyPayDays) {
        if (creditDays == 0) {
            return "Proforma";
        }
        return earlyPayDiscountPct > 0
                ? Js.num(earlyPayDiscountPct) + "/" + earlyPayDays + " net " + creditDays
                : "Net " + creditDays;
    }
}

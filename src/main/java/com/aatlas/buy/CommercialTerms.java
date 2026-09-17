package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * What a supplier's paperwork says: credit, early-settlement discount, the late-delivery
 * clause and its cap, warranty, quote validity, incoterm, invoice accuracy and capacity.
 *
 * <p>Read verbatim from {@code supplier_terms} (via {@code history.Suppliers.terms}). Every
 * field is null when the row itself is missing - "not provided" is a real state, never a
 * placeholder a formula would then score. See {@code TermsEngine}.
 *
 * @param creditDays days of credit; 0 means payment against proforma, a real cash cost
 * @param termsLabel as terms are written: "Net 45", "2/10 net 30", "Proforma", or "Not
 *     provided" when there is no terms row at all
 * @param earlyPayDiscountPct early-settlement discount, percent; 0 when none is offered
 * @param latePenaltyPctPerWeek liquidated damages, percent of order value per week late; 0 means no clause
 * @param latePenaltyCapPct the cap on that clause - the number that says whether it has teeth
 * @param capacityUnitsMonth units a month this supplier can take before the line becomes a promise
 */
@Schema(name = "CommercialTerms")
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommercialTerms(
        Integer creditDays,
        String termsLabel,
        BigDecimal earlyPayDiscountPct,
        Integer earlyPayDays,
        BigDecimal latePenaltyPctPerWeek,
        BigDecimal latePenaltyCapPct,
        Integer warrantyMonths,
        Integer quoteValidityDays,
        String incoterm,
        BigDecimal invoiceAccuracyPct,
        Integer capacityUnitsMonth) {

    /** No {@code supplier_terms} row on file: every figure "not provided", never a placeholder. */
    public static CommercialTerms notProvided() {
        return new CommercialTerms(null, "Not provided", null, null, null, null, null, null, null, null, null);
    }
}

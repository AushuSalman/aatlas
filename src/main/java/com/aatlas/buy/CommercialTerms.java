package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a supplier's paperwork says: credit, early-settlement discount, the late-delivery
 * clause and its cap, warranty, quote validity, incoterm, invoice accuracy and capacity.
 *
 * <p>A straight port of the frontend's {@code CommercialTerms} in {@code intel/terms.ts}.
 * The {@code suppliers} module (wave 1) already ported the same shape behind its own REST
 * endpoints, but its only public (package-root) type is {@code SupplierPanelSeeder} - it
 * exposes no reader for terms. Per the stand-in rule, {@code buy} carries its own copy here:
 * deterministic, keyed the same way (supplier id + country), rather than reaching into
 * {@code suppliers.internal}, which {@code ModularityTests} would fail the build for.
 *
 * <p>TODO(merge): consider depending on suppliers' public reader if one exists after merge.
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

package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Edits a supplier already on the panel. Every field is optional; only what is sent changes. */
@Schema(name = "PatchSupplierRequest")
record PatchSupplierRequest(
        @Size(max = 120) String contactName,
        @Email(message = "That does not look like an email address.") @Size(max = 254) String email,
        String category,
        TermsPatch terms) {

    /** Commercial terms, edited a field at a time. Absent fields keep their current value. */
    @Schema(name = "CommercialTermsPatch")
    record TermsPatch(
            Integer creditDays,
            Double earlyPayDiscountPct,
            Integer earlyPayDays,
            Double latePenaltyPctPerWeek,
            Double latePenaltyCapPct,
            Integer warrantyMonths,
            Integer quoteValidityDays,
            String incoterm,
            Double invoiceAccuracyPct,
            Integer capacityUnitsMonth) {
    }
}

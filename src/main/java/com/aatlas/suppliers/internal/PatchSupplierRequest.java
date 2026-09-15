package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Edits a supplier already on the panel. Every field is optional; only what is sent changes.
 *
 * <p>Covers two editors that look different on screen and are the same operation underneath:
 * the supplier form, which sends the whole record back, and the terms panel, which sends one
 * commercial field at a time. Absent means "leave it", so both work through one shape.
 *
 * <p>As with adding, <b>no stars are accepted</b>. Changing the defect rate or the on-time
 * figure re-derives the quality, delivery and pricing stars; only {@code communication} is
 * taken as given.
 */
@Schema(name = "PatchSupplierRequest")
record PatchSupplierRequest(
        @Size(max = 120) String name,
        @Size(max = 80) String country,
        @Size(max = 120) String city,
        @Size(max = 200) String website,
        @Size(max = 60) String category,

        @Min(0) @Max(200) Integer yearsTrading,
        List<@Size(max = 80) String> certifications,

        @Min(1) @Max(365) Integer leadTimeDays,
        @DecimalMin("1") @DecimalMax("100") Double otifPct,
        @DecimalMin("40") @DecimalMax("300") Double priceIndex,
        @DecimalMin("0") @DecimalMax("100") Double defectPct,
        Boolean holdsStock,
        @DecimalMin("1") @DecimalMax("5") Double communication,

        @Size(max = 120) String contactName,
        @Email(message = "That does not look like an email address.") @Size(max = 254) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Size(max = 2000) String notes,

        /** Accepted alongside {@code contactEmail} because the terms panel has always sent it. */
        @Email(message = "That does not look like an email address.") @Size(max = 254) String email,

        TermsPatch terms) {

    /**
     * Whether anything about the supplier itself changed, as opposed to only its terms.
     *
     * <p>Decides whether the rating has to be re-derived, which is worth not doing when a
     * buyer only edited a credit period.
     */
    boolean touchesProfile() {
        return name != null || country != null || city != null || website != null || category != null
                || yearsTrading != null || certifications != null || leadTimeDays != null
                || otifPct != null || priceIndex != null || defectPct != null || holdsStock != null
                || communication != null || contactName != null || contactEmail != null
                || contactPhone != null || email != null;
    }

    /** The address, from whichever of the two field names the caller used. */
    String resolvedEmail() {
        return contactEmail != null ? contactEmail : email;
    }

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

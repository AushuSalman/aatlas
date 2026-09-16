package com.aatlas.suppliers.internal;

import com.aatlas.suppliers.internal.csv.SupplierDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Putting a supplier on the panel, by either of the two routes that end here.
 *
 * <p>Send a {@code lookupId} for a supplier the platform looked up and showed the buyer, or
 * send the record itself for one they are describing from their own files. One endpoint with
 * two shapes rather than two endpoints, because from the caller's side it is one action - the
 * panel gains a supplier - and the frontend's "add" button does not care which modal it came
 * from.
 *
 * <p><b>No stars are accepted.</b> Quality, delivery and pricing are derived from the facts
 * below; only {@code communication} is taken as given, because nothing in the system measures
 * it. A client that could post its own rating could award a supplier five stars by saying so.
 */
@Schema(name = "AddSupplierRequest", description = "Either a lookupId, or the supplier record itself.")
record AddSupplierRequest(
        @Schema(description = "The id returned by POST /suppliers/lookup. Omit when describing a supplier by hand.")
                UUID lookupId,

        @Schema(example = "Meridian Copper GmbH")
                @Size(max = 120, message = "That supplier name is too long.")
                String name,

        @Schema(example = "Germany")
                @Size(max = 80)
                String country,

        @Size(max = 120) String city,
        @Size(max = 200) String website,

        @Schema(description = "One of the platform's categories. Anything else is filed under Fittings.")
                @Size(max = 60)
                String category,

        @Min(0) @Max(200) Integer yearsTrading,
        List<@Size(max = 80) String> certifications,

        @Schema(description = "Order to their gate, in days. Inbound transit is added by the platform.")
                @Min(1) @Max(365)
                Integer leadTimeDays,

        @Schema(description = "Share of orders that arrived when promised.")
                @DecimalMin("1") @DecimalMax("100")
                Double otifPct,

        @Schema(description = "100 is market; 94 is six per cent under.")
                @DecimalMin("40") @DecimalMax("300")
                Double priceIndex,

        @DecimalMin("0") @DecimalMax("100") Double defectPct,
        Boolean holdsStock,

        @Schema(description = "1-5. The only star accepted rather than derived.")
                @DecimalMin("1") @DecimalMax("5")
                Double communication,

        @Size(max = 120) String contactName,
        @Email(message = "That does not look like an email address.") @Size(max = 254) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Size(max = 2000) String notes) {

    /** Which of the two shapes this is. */
    boolean fromLookup() {
        return lookupId != null;
    }

    /**
     * Exactly one shape, and a complete one.
     *
     * <p>Checked here rather than field by field because the fields are required only in the
     * manual shape: demanding a name alongside a {@code lookupId} would reject the lookup
     * route, and demanding nothing would let a half-filled record through as a supplier with
     * no name.
     */
    @AssertTrue(message = "Send either a lookupId, or name, country, leadTimeDays and otifPct.")
    boolean isUsable() {
        if (fromLookup()) {
            // A lookup carries everything; anything else sent with it would be ignored, and
            // silently ignoring input is how a buyer's correction disappears.
            return name == null && country == null && leadTimeDays == null && otifPct == null;
        }
        return isBlank(name) == false
                && isBlank(country) == false
                && leadTimeDays != null
                && otifPct != null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The record as a draft, with every optional filled in.
     *
     * <p>Same defaults the CSV import applies to an absent column, so a supplier typed in by
     * hand and the same supplier imported from a file produce identical rows.
     */
    SupplierDraft toDraft() {
        String cleanName = name.strip();
        // Canonical, not merely stripped: this string is the shipping lane's key, and "GB"
        // or "United Kingdom" misses every row in the rate card, landing the supplier on the
        // unlisted-origin fallback - a 5% duty and a thirty-day transit on every quote they
        // are ever part of. The CSV import has always canonicalised; the same supplier typed
        // into the form reaches the same table and the same engine, so it does too.
        String cleanCountry = Countries.canonicalOr(country, country.strip());
        return new SupplierDraft(
                key(cleanName, cleanCountry),
                cleanName,
                cleanCountry,
                text(city),
                cleanUrl(website),
                text(category).isEmpty() ? SupplierDraft.DEFAULT_CATEGORY : category.strip(),
                yearsTrading == null ? SupplierDraft.DEFAULT_YEARS_TRADING : yearsTrading,
                certifications == null
                        ? List.of()
                        : certifications.stream().map(String::strip).filter(c -> !c.isEmpty()).toList(),
                leadTimeDays,
                otifPct,
                priceIndex == null ? SupplierDraft.DEFAULT_PRICE_INDEX : priceIndex,
                defectPct == null ? SupplierDraft.DEFAULT_DEFECT_PCT : defectPct,
                Boolean.TRUE.equals(holdsStock),
                communication == null ? SupplierDraft.DEFAULT_COMMUNICATION : communication,
                text(contactName),
                text(contactEmail),
                text(contactPhone),
                0);
    }

    /** Name and country, normalised. The same key the CSV import de-duplicates by. */
    static String key(String name, String country) {
        return normalise(name) + "|" + normalise(country);
    }

    private static String normalise(String value) {
        StringBuilder out = new StringBuilder();
        value.toLowerCase(Locale.ROOT).chars().filter(Character::isLetterOrDigit).forEach(c -> out.append((char) c));
        return out.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String cleanUrl(String value) {
        return text(value).toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceFirst("/+$", "");
    }
}

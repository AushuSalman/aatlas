package com.aatlas.suppliers.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A supplier as the Suppliers screen renders it: the frontend's {@code SupplierProfile},
 * field for field, plus the two columns the panel table adds ({@code risk} and
 * {@code currency}).
 *
 * <p>Nulls are omitted on the wire ({@code NON_NULL}), so the optional fields
 * ({@code addedAt}, {@code sources}, {@code watchOuts}, {@code risk}, {@code currency}) are
 * absent rather than {@code null}, as the TypeScript type declares them. Every figure that
 * can be absent for a real supplier - one known only from a purchase order, or one whose
 * profile is only partly filled in - is boxed for the same reason: {@code null} there is
 * "not provided", and the UI renders it as such rather than a fabricated zero or average.
 *
 * @param id the frontend id: {@code sup-2} for the seeded panel, {@code own-...} for one
 *     added by hand, a file or a lookup, {@code po-...} for one a purchases import created
 * @param rating 1.0-5.0, one decimal; null when the supplier has no rated dimension yet
 *     ("not assessed")
 * @param ratingSource "Verified buyers" for the panel; null when there is no rating
 * @param spendShare12m share of the last twelve months' spend, 0-1; zero for a supplier added today
 * @param priceIndex 100 = market; lower is cheaper; null when never provided
 * @param holdsStock ships from stock or can rush, the same fact the Buy screen's route shows; null = unknown
 */
@Schema(name = "SupplierProfile")
@JsonIgnoreProperties(ignoreUnknown = true)
record SupplierProfileView(
        String id,
        String name,
        String country,
        String city,
        String website,
        String category,
        Integer yearsTrading,
        List<String> certifications,
        Double rating,
        int reviewCount,
        String ratingSource,
        RatingBreakdown ratingBreakdown,
        List<SupplierReview> reviews,
        double spendShare12m,
        Integer leadTimeDays,
        Double otifPct,
        Double priceIndex,
        Double defectPct,
        Boolean holdsStock,
        boolean isCustom,
        Instant addedAt,
        List<LookupSource> sources,
        List<String> watchOuts,
        RiskSummary risk,
        String currency) {

    SupplierProfileView withPanelColumns(RiskSummary risk, String currency) {
        return new SupplierProfileView(id, name, country, city, website, category, yearsTrading, certifications,
                rating, reviewCount, ratingSource, ratingBreakdown, reviews, spendShare12m, leadTimeDays, otifPct,
                priceIndex, defectPct, holdsStock, isCustom, addedAt, sources, watchOuts, risk, currency);
    }
}

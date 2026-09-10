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
 * <p>One record serves three purposes on purpose. The lookup engine produces it, the
 * lookup table stores it verbatim as {@code jsonb}, and every read endpoint returns it.
 * If those were three shapes, the profile a buyer saw on the lookup card and the one that
 * lands on the panel could differ, which is precisely the thing "add what you looked up"
 * must not allow.
 *
 * <p>Nulls are omitted on the wire ({@code NON_NULL}), so the optional fields
 * ({@code addedAt}, {@code sources}, {@code watchOuts}, {@code risk}, {@code currency})
 * are absent rather than {@code null}, as the TypeScript type declares them.
 *
 * @param id the frontend id: {@code sup-2} for the seeded panel, {@code cus-...} for a
 *     supplier added from a lookup. Every seeded figure for the supplier hashes this.
 * @param rating 1.0-5.0, one decimal; the weighted mean of the breakdown
 * @param ratingSource "Verified buyers" for the panel; the sources found for an added supplier
 * @param spendShare12m share of the last twelve months' spend, 0-1; zero for a supplier added today
 * @param priceIndex 100 = market; lower is cheaper
 * @param holdsStock ships from stock or can rush, the same fact the Buy screen's route shows
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
        int yearsTrading,
        List<String> certifications,
        double rating,
        int reviewCount,
        String ratingSource,
        RatingBreakdown ratingBreakdown,
        List<SupplierReview> reviews,
        double spendShare12m,
        int leadTimeDays,
        double otifPct,
        double priceIndex,
        double defectPct,
        boolean holdsStock,
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

package com.aatlas.suppliers.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.suppliers.internal.csv.SupplierDraft;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turning a supplier record into panel rows.
 *
 * <p>The one place a supplier is created or updated from facts a person supplied, whether
 * they typed them into the form or uploaded a file of them. Both paths produce a
 * {@link SupplierDraft} and hand it here, so the two cannot drift - and in particular cannot
 * drift on how the rating is derived, which is the part a caller must not be able to
 * influence.
 *
 * <p>The web-lookup path does not come through here: it has a profile the platform assembled,
 * including a real review count and source, and flattening that into a draft would throw away
 * the evidence that makes those stars mean something different from self-reported ones.
 */
@Component
class SupplierWriter {

    private final SupplierRepository suppliers;
    private final SupplierTermsRepository terms;
    private final SupplierRatingRepository ratings;
    private final SupplierRiskRepository risks;
    private final SupplierPerformanceMonthRepository performance;
    private final AatlasClock clock;

    SupplierWriter(
            SupplierRepository suppliers,
            SupplierTermsRepository terms,
            SupplierRatingRepository ratings,
            SupplierRiskRepository risks,
            SupplierPerformanceMonthRepository performance,
            AatlasClock clock) {
        this.suppliers = suppliers;
        this.terms = terms;
        this.ratings = ratings;
        this.risks = risks;
        this.performance = performance;
        this.clock = clock;
    }

    /** What the rating source reads for a supplier the buyer described themselves. */
    static final String SELF_REPORTED_SOURCE = "Entered by you";

    /** And for one that arrived in a file. Both are self-reported; only the route differs. */
    static final String IMPORTED_SOURCE = "Imported from your file";

    SupplierEntity create(UUID tenantId, String supplierKey, SupplierDraft draft, UUID userId, String ratingSource) {
        Instant now = clock.now();
        String vendorCode = "V-" + (Math.floorMod(supplierKey.hashCode(), 9000) + 1000);

        SupplierEntity supplier = new SupplierEntity(
                supplierKey,
                vendorCode,
                draft.name(),
                draft.country(),
                draft.city(),
                draft.website(),
                draft.category(),
                draft.contactName(),
                draft.contactEmail(),
                Currencies.forCountry(draft.country()),
                draft.leadTimeDays(),
                draft.otifPct(),
                draft.priceIndex(),
                draft.defectPct(),
                draft.holdsStock(),
                draft.yearsTrading(),
                // Spend and order history are facts about trading with them, which describing
                // a supplier does not carry. They fill in as purchase orders arrive.
                0,
                BigDecimal.ZERO,
                0,
                true,
                userId,
                now,
                clock.today());
        supplier.setTenantId(tenantId);
        supplier = suppliers.save(supplier);

        terms.save(new SupplierTermsEntity(
                supplier.getId(),
                tenantId,
                TermsScoring.commercialTerms(supplierKey, draft.country()),
                1,
                1,
                (int) Math.round(draft.defectPct() * 10_000),
                24,
                draft.certifications()));

        RatingBreakdown breakdown = breakdownFor(supplierKey, draft);
        double overall = overall(breakdown);
        ratings.save(new SupplierRatingEntity(
                supplier.getId(),
                tenantId,
                overall,
                // No reviews: these numbers came from the buyer, and a review count would make
                // the supplier look independently verified when it is not.
                0,
                breakdown,
                SupplierScoring.ratingLabel(overall),
                ratingSource,
                now));

        writeDerived(tenantId, supplier, supplierKey, draft, overall, now);
        return supplier;
    }

    /**
     * Risk and the on-time trend, which every supplier screen expects to exist.
     *
     * <p>Written here rather than left to a nightly job because a supplier added today has to
     * open on the panel today, and a profile with no risk score reads as broken rather than new.
     */
    private void writeDerived(UUID tenantId, SupplierEntity supplier, String supplierKey,
            SupplierDraft draft, double rating, Instant now) {
        SupplierRisk risk = RiskScoring.supplierRisk(
                new RiskScoring.Input(supplierKey, draft.leadTimeDays(), draft.otifPct(), draft.defectPct()),
                new RiskScoring.RatingSummary(rating, 0), now);
        SupplierRiskEntity riskRow = new SupplierRiskEntity(supplier.getId(), tenantId, risk);
        risks.save(riskRow);

        List<Double> trend = SupplierScoring.otifTrendFor("custom:" + supplierKey, draft.otifPct());
        YearMonth thisMonth = YearMonth.from(clock.today());
        List<SupplierPerformanceMonthEntity> months = new ArrayList<>();
        for (int i = 0; i < trend.size(); i++) {
            LocalDate month = thisMonth.minusMonths((long) trend.size() - 1 - i).atDay(1);
            SupplierPerformanceMonthEntity row =
                    new SupplierPerformanceMonthEntity(supplier.getId(), month, trend.get(i));
            row.setTenantId(tenantId);
            months.add(row);
        }
        performance.saveAll(months);
    }

    /** Applies a corrected record, leaving the trading history this platform observed alone. */
    SupplierEntity update(SupplierEntity supplier, SupplierDraft draft, String ratingSource) {
        supplier.applyImport(
                draft.name(),
                draft.country(),
                draft.city(),
                draft.website(),
                draft.category(),
                draft.contactName(),
                draft.contactEmail(),
                Currencies.forCountry(draft.country()),
                draft.leadTimeDays(),
                draft.otifPct(),
                draft.priceIndex(),
                draft.defectPct(),
                draft.holdsStock(),
                draft.yearsTrading());
        SupplierEntity saved = suppliers.save(supplier);

        terms.findById(saved.getId())
                .ifPresent(row -> {
                    row.applyCertifications(draft.certifications());
                    terms.save(row);
                });

        ratings.findById(saved.getId()).ifPresent(rating -> {
            RatingBreakdown breakdown = breakdownFor(saved.getSupplierKey(), draft);
            double overall = overall(breakdown);
            rating.recompute(overall, breakdown, SupplierScoring.ratingLabel(overall), ratingSource, clock.now());
            ratings.save(rating);
        });

        return saved;
    }

    /**
     * The four stars, derived rather than taken.
     *
     * <p>Three are computed from numbers the record supplied; only communication is accepted
     * as given, because nothing in the system measures it. That asymmetry is the point - a
     * supplier cannot be awarded five stars by writing five in a file.
     */
    private static RatingBreakdown breakdownFor(String supplierKey, SupplierDraft draft) {
        return new RatingBreakdown(
                SupplierScoring.qualityStars(draft.defectPct()),
                SupplierScoring.deliveryStars(supplierKey, draft.country(), draft.leadTimeDays(), draft.otifPct()),
                Math.max(1, Math.min(5, draft.communication())),
                SupplierScoring.pricingStars(draft.priceIndex()));
    }

    private static double overall(RatingBreakdown b) {
        double mean = (b.quality() + b.delivery() + b.communication() + b.pricing()) / 4;
        return Math.round(mean * 10) / 10.0;
    }
}

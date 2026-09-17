package com.aatlas.suppliers.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.suppliers.internal.csv.SupplierDraft;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turning a supplier record into panel rows.
 *
 * <p>The one place a supplier is created or updated from facts a person supplied, whether
 * they typed them into the form, uploaded a file of them, or completed one after an honest
 * "we found nothing" lookup. All three paths produce a {@link SupplierDraft} and hand it
 * here, so they cannot drift - and in particular cannot drift on how the rating is derived,
 * which is the part a caller must not be able to influence.
 *
 * <p>No commercial terms are invented (see {@link TermsScoring}): a freshly created supplier
 * has no {@code supplier_terms} figures beyond whatever certifications the record carried,
 * and no six-month performance trend - a walk seeded around a claimed on-time rate would be
 * exactly the fabrication this rewrite removes. The rating and risk stored here are the
 * supplier's day-one snapshot from provided figures only; reads that want the tenant's
 * observed purchase history use {@code history.PurchaseHistory} directly (see
 * {@link SuppliersService}).
 */
@Component
class SupplierWriter {

    private final SupplierRepository suppliers;
    private final SupplierTermsRepository terms;
    private final SupplierRatingRepository ratings;
    private final SupplierRiskRepository risks;
    private final AatlasClock clock;

    SupplierWriter(
            SupplierRepository suppliers,
            SupplierTermsRepository terms,
            SupplierRatingRepository ratings,
            SupplierRiskRepository risks,
            AatlasClock clock) {
        this.suppliers = suppliers;
        this.terms = terms;
        this.ratings = ratings;
        this.risks = risks;
        this.clock = clock;
    }

    /** What the rating source reads for a supplier the buyer described themselves. */
    static final String SELF_REPORTED_SOURCE = "Entered by you";

    /** And for one that arrived in a file. Both are self-reported; only the route differs. */
    static final String IMPORTED_SOURCE = "Imported from your file";

    /** The {@code suppliers.source} column: typed into the panel form. */
    static final String MANUAL_SOURCE = "manual";

    /** The {@code suppliers.source} column: rows from a supplier-list CSV. */
    static final String IMPORT_SOURCE = "import";

    /** The {@code suppliers.source} column: completed after a "we found nothing" lookup. */
    static final String LOOKUP_SOURCE = "lookup";

    SupplierEntity create(UUID tenantId, String supplierKey, SupplierDraft draft, UUID userId, String source,
            String ratingSource) {
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
        supplier.setSource(source);
        supplier = suppliers.save(supplier);

        // Certifications are the only real fact a fresh record carries about terms; every
        // commercial figure stays null (TermsScoring never seeds one) until a person or a
        // file states it.
        terms.save(new SupplierTermsEntity(
                supplier.getId(),
                tenantId,
                TermsScoring.unspecified(),
                1,
                1,
                (int) Math.round(draft.defectPct() * 10_000),
                24,
                draft.certifications()));

        RatingBreakdown breakdown = breakdownFor(draft.country(), draft);
        Double overall = SupplierScoring.overall(breakdown);
        if (overall != null) {
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
        }

        writeDerivedRisk(tenantId, supplier, draft, overall, now);
        return supplier;
    }

    /**
     * The day-one risk snapshot, from whatever the record supplied - never observed, since a
     * supplier just created has no purchase history yet. Reads that want the tenant's
     * observed picture recompute it from {@code history.PurchaseHistory} instead of trusting
     * this row as it ages; see {@link SuppliersService}.
     */
    private void writeDerivedRisk(UUID tenantId, SupplierEntity supplier, SupplierDraft draft, Double rating,
            Instant now) {
        RiskScoring.Input input = new RiskScoring.Input(
                false, draft.otifPct(), draft.defectPct(), null, null, null, null, null, null);
        RiskScoring.RatingSummary ratingSummary = rating == null ? null : new RiskScoring.RatingSummary(rating, 0);
        SupplierRisk risk = RiskScoring.supplierRisk(input, ratingSummary, now);
        risks.save(new SupplierRiskEntity(supplier.getId(), tenantId, risk));
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

        RatingBreakdown breakdown = breakdownFor(saved.getCountry(), draft);
        Double overall = SupplierScoring.overall(breakdown);
        Instant now = clock.now();
        java.util.Optional<SupplierRatingEntity> existing = ratings.findById(saved.getId());
        if (overall == null) {
            // Corrected down to nothing scoreable: no rating row claims a figure that no
            // longer has an input behind it.
            existing.ifPresent(ratings::delete);
        } else if (existing.isPresent()) {
            SupplierRatingEntity rating = existing.get();
            rating.recompute(overall, breakdown, SupplierScoring.ratingLabel(overall), ratingSource, now);
            ratings.save(rating);
        } else {
            ratings.save(new SupplierRatingEntity(saved.getId(), saved.getTenantId(), overall, 0, breakdown,
                    SupplierScoring.ratingLabel(overall), ratingSource, now));
        }

        return saved;
    }

    /**
     * The four stars, derived rather than taken.
     *
     * <p>Three are computed from numbers the record supplied, and are {@code null} when that
     * number is absent; only communication is accepted as given, because nothing in the
     * system measures it - that asymmetry is the point, a supplier cannot be awarded five
     * stars by writing five in a file.
     */
    private static RatingBreakdown breakdownFor(String country, SupplierDraft draft) {
        return new RatingBreakdown(
                SupplierScoring.qualityStars(draft.defectPct()),
                SupplierScoring.deliveryStars(country, draft.leadTimeDays(), draft.otifPct(), draft.holdsStock()),
                draft.communication() == null ? null : Js.clamp(draft.communication(), 1, 5),
                SupplierScoring.pricingStars(draft.priceIndex()));
    }
}

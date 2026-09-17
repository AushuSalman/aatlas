package com.aatlas.suppliers.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.suppliers.SupplierPanelSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies the eight seeded suppliers - their terms, ratings, reviews, risk and six-month
 * on-time trend - from {@code seed/suppliers.json} into a tenant.
 *
 * <p>Runs outside any request's {@code TenantContext} (the event listener that calls this
 * fires on a Modulith worker thread, which does not inherit the request's thread-local), so
 * every row is stamped with {@code tenantId} explicitly rather than relying on
 * {@code TenantScopedEntity}'s auto-stamp.
 */
@Service
class SupplierPanelSeederImpl implements SupplierPanelSeeder {

    private static final Logger log = LoggerFactory.getLogger(SupplierPanelSeederImpl.class);
    private static final String SEED_PATH = "classpath:seed/suppliers.json";

    private final SupplierRepository suppliers;
    private final SupplierTermsRepository terms;
    private final SupplierRatingRepository ratings;
    private final SupplierRiskRepository risks;
    private final SupplierPerformanceMonthRepository performance;
    private final SupplierProductLinkSeeder links;
    private final ObjectMapper json;
    private final AatlasClock clock;

    SupplierPanelSeederImpl(
            SupplierRepository suppliers,
            SupplierTermsRepository terms,
            SupplierRatingRepository ratings,
            SupplierRiskRepository risks,
            SupplierPerformanceMonthRepository performance,
            SupplierProductLinkSeeder links,
            ObjectMapper json,
            AatlasClock clock) {
        this.suppliers = suppliers;
        this.terms = terms;
        this.ratings = ratings;
        this.risks = risks;
        this.performance = performance;
        this.links = links;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int seedForTenant(UUID tenantId) {
        List<SeedSupplierEntry> entries = readSeed();
        int added = 0;
        for (SeedSupplierEntry entry : entries) {
            if (suppliers.existsByTenantIdAndSupplierKey(tenantId, entry.profile().id())) {
                continue;
            }
            seedOne(tenantId, entry);
            added++;
        }
        // The rows above are still in the persistence context; the link insert is plain JDBC
        // and does not see them. Without this flush the cross join misses whatever Hibernate
        // has not written yet - it produced 105 links instead of 120 (seven suppliers, not
        // eight) until the flush was added, which is the kind of shortfall that looks like a
        // seeding rule rather than a bug.
        suppliers.flush();

        // Which supplier can quote on which product. Runs on every call, not only when
        // suppliers were added: the catalogue may have arrived after the panel did, and the
        // insert is idempotent, so this is what picks up products seeded since. A tenant with
        // no catalogue yet links nothing and that is not an error.
        int linked = links.linkAllForTenant(tenantId);
        log.info("Seeded {} of {} suppliers for tenant {} ({} product links)",
                added, entries.size(), tenantId, linked);
        return added;
    }

    private void seedOne(UUID tenantId, SeedSupplierEntry entry) {
        SupplierProfileView profile = entry.profile();
        SeedSupplierRecord record = entry.supplierRecord();
        Instant now = clock.now();

        SupplierEntity supplier = new SupplierEntity(
                profile.id(),
                record.vendorCode(),
                profile.name(),
                profile.country(),
                profile.city(),
                profile.website(),
                profile.category(),
                record.contactName(),
                record.email(),
                record.currency(),
                profile.leadTimeDays(),
                profile.otifPct(),
                profile.priceIndex(),
                profile.defectPct(),
                profile.holdsStock(),
                profile.yearsTrading(),
                profile.spendShare12m(),
                record.spendYtd(),
                record.poCount12m(),
                false,
                null,
                null,
                record.since());
        supplier.setTenantId(tenantId);
        supplier.setSource("sample");
        supplier = suppliers.save(supplier);

        SupplierTermsEntity termsRow = new SupplierTermsEntity(
                supplier.getId(), tenantId, entry.terms(), record.moq(), record.orderMultiple(),
                record.qualityPpm(), record.responseHours(), profile.certifications());
        terms.save(termsRow);

        String label = SupplierScoring.ratingLabel(profile.rating());
        ratings.save(new SupplierRatingEntity(supplier.getId(), tenantId, profile.rating(), profile.reviewCount(),
                profile.ratingBreakdown(), label, profile.ratingSource(), now));

        // No reviews are written for the seeded panel either: reviewsFor() answers [] for
        // every supplier today (no real review data exists anywhere in the platform), so a
        // row here would be dead data nothing ever serves.

        // Provided-only day-one snapshot, same as any other freshly created supplier
        // (SupplierWriter): the sample's purchase history loads separately, and reads that
        // want the observed picture recompute it live from history.PurchaseHistory instead
        // of trusting this row (see SuppliersService.computeRisk).
        RiskScoring.Input riskInput = new RiskScoring.Input(
                false, profile.otifPct(), profile.defectPct(), null, null, null, null, null, null);
        SupplierRisk risk = RiskScoring.supplierRisk(riskInput,
                new RiskScoring.RatingSummary(profile.rating(), profile.reviewCount()), now);
        SupplierRiskEntity riskRow = new SupplierRiskEntity(supplier.getId(), tenantId, risk);
        risks.save(riskRow);

        List<Double> trend = record.otifTrend();
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

    private List<SeedSupplierEntry> readSeed() {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource(SEED_PATH);
            try (InputStream in = resource.getInputStream()) {
                return json.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<List<SeedSupplierEntry>>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SEED_PATH, e);
        }
    }
}

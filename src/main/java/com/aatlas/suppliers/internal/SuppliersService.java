package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.seed.Seeded;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier panel: reads that assemble a stored {@code SupplierProfile}, and the three
 * writes that change the panel - a lookup, adding what was found, and editing or removing a
 * custom supplier.
 *
 * <p>Tenant, user and seat all come from {@link TenantContext}, bound by
 * {@code TenantContextFilter} for the life of the request; nothing here accepts any of the
 * three as a parameter from the caller.
 */
@Service
class SuppliersService {

    private final SupplierRepository suppliers;
    private final SupplierTermsRepository terms;
    private final SupplierRatingRepository ratings;
    private final SupplierReviewRepository reviews;
    private final SupplierRiskRepository risks;
    private final SupplierPerformanceMonthRepository performance;
    private final SupplierLookupRepository lookups;
    private final ObjectMapper json;
    private final AatlasClock clock;
    private final PolicyReader policy;

    SuppliersService(
            SupplierRepository suppliers,
            SupplierTermsRepository terms,
            SupplierRatingRepository ratings,
            SupplierReviewRepository reviews,
            SupplierRiskRepository risks,
            SupplierPerformanceMonthRepository performance,
            SupplierLookupRepository lookups,
            ObjectMapper json,
            AatlasClock clock,
            PolicyReader policy) {
        this.suppliers = suppliers;
        this.terms = terms;
        this.ratings = ratings;
        this.reviews = reviews;
        this.risks = risks;
        this.performance = performance;
        this.lookups = lookups;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
    }

    // -- Reads ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    List<SupplierProfileView> panel() {
        UUID tenantId = TenantContext.requireTenantId();
        Map<UUID, SupplierEntity> byId = suppliers.findByTenantIdOrderByIdAsc(tenantId).stream()
                .collect(Collectors.toMap(SupplierEntity::getId, e -> e));
        Map<UUID, SupplierTermsEntity> termsById = terms.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(SupplierTermsEntity::getSupplierId, e -> e));
        Map<UUID, SupplierRiskEntity> riskById = risks.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(SupplierRiskEntity::getSupplierId, e -> e));
        Map<UUID, List<SupplierReviewEntity>> reviewsBySupplier =
                reviews.findByTenantIdOrderBySupplierIdAscPositionAsc(tenantId).stream()
                        .collect(Collectors.groupingBy(SupplierReviewEntity::getSupplierId));

        List<SupplierProfileView> out = new ArrayList<>();
        for (SupplierRatingEntity rating : ratings.findByTenantIdOrderByRatingDesc(tenantId)) {
            SupplierEntity s = byId.get(rating.getSupplierId());
            if (s == null) {
                continue;
            }
            out.add(toView(s, rating, termsById.get(s.getId()),
                    reviewsBySupplier.getOrDefault(s.getId(), List.of()), riskById.get(s.getId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    PanelSummary.View summary() {
        UUID tenantId = TenantContext.requireTenantId();
        Map<UUID, SupplierRatingEntity> ratingBySupplier = ratings.findByTenantIdOrderByRatingDesc(tenantId).stream()
                .collect(Collectors.toMap(SupplierRatingEntity::getSupplierId, e -> e));

        List<SupplierScoring.PanelRow> rows = new ArrayList<>();
        Set<String> currencies = new HashSet<>();
        for (SupplierEntity s : suppliers.findByTenantIdOrderByIdAsc(tenantId)) {
            SupplierRatingEntity rating = ratingBySupplier.get(s.getId());
            if (rating == null) {
                continue;
            }
            rows.add(new SupplierScoring.PanelRow(s.isCustom(), rating.getRating(), s.getSpendShare12m(),
                    s.getOtifPct()));
            currencies.add(s.getCurrency());
        }
        return PanelSummary.View.of(SupplierScoring.panelSummary(rows), currencies.size());
    }

    @Transactional(readOnly = true)
    SupplierProfileView get(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        return toView(s, requireRating(tenantId, s), terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                        .orElse(null),
                reviews.findByTenantIdAndSupplierIdOrderByPositionAsc(tenantId, s.getId()),
                risks.findBySupplierIdAndTenantId(s.getId(), tenantId).orElse(null));
    }

    @Transactional(readOnly = true)
    TermsResponse terms(String supplierKey, int orderQty) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        SupplierTermsEntity row = terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                .orElseThrow(() -> ApiException.notFound("Supplier terms", supplierKey));
        CommercialTerms t = row.toCommercialTerms();
        TermsResponse.Labels labels = new TermsResponse.Labels(
                TermsScoring.creditLabel(t), TermsScoring.earlyPayLabel(t), TermsScoring.latePenaltyLabel(t));
        return new TermsResponse(t, labels, TermsScoring.termsWatchOuts(t, orderQty));
    }

    @Transactional(readOnly = true)
    RatingResponse rating(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        SupplierRatingEntity row = requireRating(tenantId, s);
        RatingBreakdown breakdown = row.toBreakdown();
        String recommendation = SupplierScoring.recommendation(
                row.getRating(), row.getReviewCount(), breakdown, s.getPriceIndex(), false);
        return new RatingResponse(row.getRating(), row.getReviewCount(), row.getLabel(), breakdown,
                row.getRatingSource(), recommendation);
    }

    @Transactional(readOnly = true)
    List<SupplierReview> reviewsFor(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        return reviews.findByTenantIdAndSupplierIdOrderByPositionAsc(tenantId, s.getId()).stream()
                .map(SupplierReviewEntity::toReview)
                .toList();
    }

    @Transactional(readOnly = true)
    SupplierRisk risk(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        return risks.findBySupplierIdAndTenantId(s.getId(), tenantId)
                .orElseThrow(() -> ApiException.notFound("Supplier risk", supplierKey))
                .toSupplierRisk();
    }

    @Transactional(readOnly = true)
    PerformanceResponse performance(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        List<Double> trend = performance.findByTenantIdAndSupplierIdOrderByMonthAsc(tenantId, s.getId()).stream()
                .map(SupplierPerformanceMonthEntity::getOtifPct)
                .toList();
        return new PerformanceResponse(trend, s.getSpendYtd(), s.getPoCount12m(), s.getSince());
    }

    // -- Writes -----------------------------------------------------------------------------------

    @Transactional
    LookupResponse lookup(LookupRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        LookupScoring.Outcome outcome = LookupScoring.lookup(request.query(), request.country());

        SupplierLookupEntity row = new SupplierLookupEntity(
                outcome.query(),
                outcome.profile().country(),
                outcome.profile().id(),
                writeJson(outcome.profile()),
                writeJson(outcome.sources()),
                outcome.watchOuts(),
                outcome.recommendation(),
                "complete",
                TenantContext.currentUserId().orElse(null));
        row.setTenantId(tenantId);
        row = lookups.save(row);

        return new LookupResponse(outcome.query(), outcome.profile(), outcome.sources(), outcome.watchOuts(),
                outcome.recommendation(), row.getId());
    }

    @Transactional
    SupplierProfileView add(AddSupplierRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        requireBuySeatOrDirector();

        SupplierLookupEntity lookupRow = lookups.findByTenantIdAndId(tenantId, request.lookupId())
                .orElseThrow(() -> ApiException.notFound("Supplier lookup", request.lookupId()));
        if (suppliers.existsByTenantIdAndSupplierKey(tenantId, lookupRow.getSupplierKey())) {
            throw ApiException.conflict("already_added", "This supplier is already on the panel.");
        }
        SupplierProfileView profile = readJson(lookupRow.getProfileJson(), SupplierProfileView.class);
        CommercialTerms commercialTerms = TermsScoring.commercialTerms(profile.id(), profile.country());
        Instant now = clock.now();
        UUID userId = TenantContext.currentUserId().orElse(null);

        String vendorCode = "V-" + Seeded.randInt(profile.id(), "vendor-code", 1000, 9999);
        SupplierEntity supplier = new SupplierEntity(
                profile.id(), vendorCode, profile.name(), profile.country(), profile.city(), profile.website(),
                profile.category(), "", "sales@" + profile.website(), Currencies.forCountry(profile.country()),
                profile.leadTimeDays(), profile.otifPct(), profile.priceIndex(), profile.defectPct(),
                profile.holdsStock(), profile.yearsTrading(), 0, BigDecimal.ZERO, 0, true, userId, now,
                clock.today());
        supplier.setTenantId(tenantId);
        supplier = suppliers.save(supplier);

        String key = "sup:" + profile.id();
        int qualityPpm = (int) Math.round(profile.defectPct() * 10000);
        int responseHours = Seeded.randInt(key, "resp-h", 4, 72);
        SupplierTermsEntity termsRow = new SupplierTermsEntity(supplier.getId(), tenantId, commercialTerms, 1, 1,
                qualityPpm, responseHours, profile.certifications());
        terms.save(termsRow);

        String label = SupplierScoring.ratingLabel(profile.rating());
        SupplierRatingEntity ratingRow = ratings.save(new SupplierRatingEntity(supplier.getId(), tenantId,
                profile.rating(), profile.reviewCount(), profile.ratingBreakdown(), label, profile.ratingSource(),
                now));

        List<SupplierReviewEntity> reviewRows = new ArrayList<>();
        int position = 0;
        for (SupplierReview r : profile.reviews()) {
            SupplierReviewEntity row = new SupplierReviewEntity(supplier.getId(), position++, r,
                    profile.ratingSource());
            row.setTenantId(tenantId);
            reviewRows.add(row);
        }
        reviews.saveAll(reviewRows);

        RiskScoring.Input riskInput = new RiskScoring.Input(
                profile.id(), profile.leadTimeDays(), profile.otifPct(), profile.defectPct());
        SupplierRisk risk = RiskScoring.supplierRisk(riskInput,
                new RiskScoring.RatingSummary(profile.rating(), profile.reviewCount()), now);
        SupplierRiskEntity riskRow = new SupplierRiskEntity(supplier.getId(), tenantId, risk);
        risks.save(riskRow);

        List<Double> trend = SupplierScoring.otifTrendFor("custom:" + profile.id(), profile.otifPct());
        YearMonth thisMonth = YearMonth.from(clock.today());
        List<SupplierPerformanceMonthEntity> months = new ArrayList<>();
        for (int i = 0; i < trend.size(); i++) {
            LocalDate month = thisMonth.minusMonths((long) trend.size() - 1 - i).atDay(1);
            SupplierPerformanceMonthEntity row = new SupplierPerformanceMonthEntity(supplier.getId(), month,
                    trend.get(i));
            row.setTenantId(tenantId);
            months.add(row);
        }
        performance.saveAll(months);

        lookupRow.setStatus("added");
        lookups.save(lookupRow);

        return toView(supplier, ratingRow, termsRow, reviewRows, riskRow);
    }

    @Transactional
    SupplierProfileView patch(String supplierKey, PatchSupplierRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        if (request.contactName() != null) {
            s.setContactName(request.contactName());
        }
        if (request.email() != null) {
            s.setEmail(request.email());
        }
        if (request.category() != null) {
            s.setCategory(request.category());
        }
        suppliers.save(s);

        if (request.terms() != null) {
            SupplierTermsEntity termsRow = terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                    .orElseThrow(() -> ApiException.notFound("Supplier terms", supplierKey));
            termsRow.apply(patched(termsRow.toCommercialTerms(), request.terms()));
            terms.save(termsRow);
        }
        return get(supplierKey);
    }

    @Transactional
    void delete(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        if (!s.isCustom()) {
            throw ApiException.conflict("seeded_supplier", "Only suppliers added from a lookup can be removed.");
        }
        // The FKs from terms, ratings, risk, reviews and performance months to suppliers are
        // all ON DELETE CASCADE (V8__suppliers.sql), so one delete clears the whole row.
        suppliers.delete(s);
    }

    // -- Helpers ------------------------------------------------------------------------------

    private static CommercialTerms patched(CommercialTerms current, PatchSupplierRequest.TermsPatch p) {
        int creditDays = p.creditDays() != null ? p.creditDays() : current.creditDays();
        double earlyPayDiscountPct =
                p.earlyPayDiscountPct() != null ? p.earlyPayDiscountPct() : current.earlyPayDiscountPct();
        int earlyPayDays = p.earlyPayDays() != null ? p.earlyPayDays() : current.earlyPayDays();
        String label = CommercialTerms.labelFor(creditDays, earlyPayDiscountPct, earlyPayDays);
        return new CommercialTerms(
                creditDays,
                label,
                earlyPayDiscountPct,
                earlyPayDays,
                p.latePenaltyPctPerWeek() != null ? p.latePenaltyPctPerWeek() : current.latePenaltyPctPerWeek(),
                p.latePenaltyCapPct() != null ? p.latePenaltyCapPct() : current.latePenaltyCapPct(),
                p.warrantyMonths() != null ? p.warrantyMonths() : current.warrantyMonths(),
                p.quoteValidityDays() != null ? p.quoteValidityDays() : current.quoteValidityDays(),
                p.incoterm() != null ? p.incoterm() : current.incoterm(),
                p.invoiceAccuracyPct() != null ? p.invoiceAccuracyPct() : current.invoiceAccuracyPct(),
                p.capacityUnitsMonth() != null ? p.capacityUnitsMonth() : current.capacityUnitsMonth());
    }

    private SupplierEntity requireSupplier(UUID tenantId, String supplierKey) {
        return suppliers.findByTenantIdAndSupplierKey(tenantId, supplierKey)
                .orElseThrow(() -> ApiException.notFound("Supplier", supplierKey));
    }

    private SupplierRatingEntity requireRating(UUID tenantId, SupplierEntity s) {
        return ratings.findBySupplierIdAndTenantId(s.getId(), tenantId)
                .orElseThrow(() -> ApiException.notFound("Supplier rating", s.getSupplierKey()));
    }

    private static SupplierProfileView toView(SupplierEntity s, SupplierRatingEntity rating,
            SupplierTermsEntity termsRow, List<SupplierReviewEntity> reviewRows, SupplierRiskEntity riskRow) {
        List<SupplierReview> reviewList = reviewRows.stream()
                .sorted(Comparator.comparingInt(SupplierReviewEntity::getPosition))
                .map(SupplierReviewEntity::toReview)
                .toList();
        List<String> certifications = termsRow != null ? termsRow.getCertifications() : List.of();
        RiskSummary riskSummary = riskRow != null ? riskRow.toSummary() : null;
        return new SupplierProfileView(
                s.getSupplierKey(), s.getName(), s.getCountry(), s.getCity(), s.getWebsite(), s.getCategory(),
                s.getYearsTrading(), certifications, rating.getRating(), rating.getReviewCount(),
                rating.getRatingSource(), rating.toBreakdown(), reviewList, s.getSpendShare12m(),
                s.getLeadTimeDays(), s.getOtifPct(), s.getPriceIndex(), s.getDefectPct(), s.isHoldsStock(),
                s.isCustom(), s.getAddedAt(), null, null, riskSummary, s.getCurrency());
    }

    /** Buy seats (purchase manager, buyer, purchase head) and the commercial director. */
    private void requireBuySeatOrDirector() {
        String role = currentRole();
        if ("both".equals(role)) {
            return;
        }
        Persona persona = policy.personaFor(TenantContext.requireTenantId(), role);
        if (persona.side() != Persona.Side.BUY) {
            throw notAllowed();
        }
    }

    /** The commercial director alone - the seat whose wire value is {@code both}. */
    void requireDirector() {
        if (!"both".equals(currentRole())) {
            throw notAllowed();
        }
    }

    private static String currentRole() {
        String role = TenantContext.current().map(TenantContext.Actor::role).orElse(null);
        if (role == null) {
            throw notAllowed();
        }
        return role;
    }

    private static ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "not_allowed", "This seat may not perform that action.");
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (IOException e) {
            throw new IllegalStateException("Could not deserialise " + type.getSimpleName(), e);
        }
    }
}

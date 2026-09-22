package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Window;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.aatlas.suppliers.internal.csv.SupplierDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier panel: reads that assemble a {@code SupplierProfile} from the tenant's own
 * rows plus its observed purchase history, and the writes that change the panel - a lookup,
 * adding what was drafted, and editing or removing a custom supplier.
 *
 * <p>Fulfilment risk (see {@link RiskScoring}) is computed fresh on every read from
 * {@link PurchaseHistory} rather than trusted from a stored snapshot: a supplier's observed
 * on-time rate and lead-time variance change as purchase orders are received, and a row
 * written once at creation would go stale the moment the first order lands. The
 * {@code supplier_risk} table still holds each supplier's day-one snapshot ({@link
 * SupplierWriter}), kept for other consumers, but this module's own reads never rely on its
 * age.
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
    private final SupplierPerformanceMonthRepository performance;
    private final SupplierLookupRepository lookups;
    private final ObjectMapper json;
    private final AatlasClock clock;
    private final PolicyReader policy;
    private final SupplierWriter writer;
    private final SupplierProductLinkSeeder links;
    private final PurchaseHistory purchaseHistory;
    private final HistoryCaches caches;

    SuppliersService(
            SupplierRepository suppliers,
            SupplierTermsRepository terms,
            SupplierRatingRepository ratings,
            SupplierPerformanceMonthRepository performance,
            SupplierLookupRepository lookups,
            ObjectMapper json,
            AatlasClock clock,
            PolicyReader policy,
            SupplierWriter writer,
            SupplierProductLinkSeeder links,
            PurchaseHistory purchaseHistory,
            HistoryCaches caches) {
        this.suppliers = suppliers;
        this.terms = terms;
        this.ratings = ratings;
        this.performance = performance;
        this.lookups = lookups;
        this.json = json;
        this.clock = clock;
        this.policy = policy;
        this.writer = writer;
        this.links = links;
        this.purchaseHistory = purchaseHistory;
        this.caches = caches;
    }

    // -- Reads ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    List<SupplierProfileView> panel() {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate today = clock.today();
        List<SupplierEntity> all = suppliers.findByTenantIdOrderByIdAsc(tenantId);
        Map<UUID, SupplierRatingEntity> ratingById = ratings.findByTenantIdOrderByRatingDesc(tenantId).stream()
                .collect(Collectors.toMap(SupplierRatingEntity::getSupplierId, e -> e));
        Map<UUID, SupplierTermsEntity> termsById = terms.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(SupplierTermsEntity::getSupplierId, e -> e));
        Map<String, PurchaseHistory.PoStats> w90 = w90BySupplierKey(today);

        List<SupplierProfileView> out = new ArrayList<>();
        for (SupplierEntity s : all) {
            SupplierRatingEntity rating = ratingById.get(s.getId());
            SupplierTermsEntity termsRow = termsById.get(s.getId());
            SupplierRisk risk = computeRisk(s, termsRow, rating, w90, today);
            out.add(toView(s, rating, termsRow, risk));
        }
        out.sort(Comparator.comparing(
                SupplierProfileView::rating, Comparator.nullsLast(Comparator.<Double>reverseOrder())));
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
            rows.add(new SupplierScoring.PanelRow(s.isCustom(), rating == null ? null : rating.getRating(),
                    s.getSpendShare12m(), s.getOtifPct()));
            currencies.add(s.getCurrency());
        }
        return PanelSummary.View.of(SupplierScoring.panelSummary(rows), currencies.size());
    }

    @Transactional(readOnly = true)
    SupplierProfileView get(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        return viewOf(tenantId, s);
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

    /** No real review data exists anywhere in the platform today; the panel says so honestly. */
    @Transactional(readOnly = true)
    List<SupplierReview> reviewsFor(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        requireSupplier(tenantId, supplierKey);
        return List.of();
    }

    /**
     * Fulfilment risk, computed fresh from this tenant's observed purchase history and the
     * supplier's own provided figures - see {@link RiskScoring}. Always answers, even for a
     * supplier known only by name: {@code score}/{@code level} are null and {@code factors} is
     * empty rather than a 404, because "not assessed" is itself the honest answer.
     */
    @Transactional(readOnly = true)
    SupplierRisk risk(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        SupplierRatingEntity rating = ratings.findBySupplierIdAndTenantId(s.getId(), tenantId).orElse(null);
        SupplierTermsEntity termsRow = terms.findBySupplierIdAndTenantId(s.getId(), tenantId).orElse(null);
        LocalDate today = clock.today();
        return computeRisk(s, termsRow, rating, w90BySupplierKey(today), today);
    }

    @Transactional(readOnly = true)
    PerformanceResponse performance(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        LocalDate today = clock.today();
        PurchaseHistory.SupplierPurchases hist = purchaseHistory.supplier(s.getSupplierKey(), today);

        List<Double> observedTrend = hist.otifTrend().stream()
                .map(m -> m.otifPct() == null ? null : m.otifPct().doubleValue())
                .toList();
        List<Double> otifTrend = observedTrend.stream().anyMatch(Objects::nonNull)
                ? observedTrend
                : performance.findByTenantIdAndSupplierIdOrderByMonthAsc(tenantId, s.getId()).stream()
                        .map(SupplierPerformanceMonthEntity::getOtifPct)
                        .toList();

        BigDecimal spendYtd = hist.w12().spend();
        int poCount12m = (int) hist.w12().pos();
        LocalDate since = hist.allTime().firstOrder() != null ? hist.allTime().firstOrder() : s.getSince();
        return new PerformanceResponse(otifTrend, spendYtd, poCount12m, since);
    }

    // -- Writes -----------------------------------------------------------------------------------

    @Transactional
    LookupResponse lookup(LookupRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        LookupScoring.Outcome outcome = LookupScoring.lookup(request.query(), request.country());

        SupplierLookupEntity row = new SupplierLookupEntity(
                outcome.query(),
                outcome.country() == null ? "" : outcome.country(),
                "",
                writeJson(outcome.draft()),
                "[]",
                List.of(),
                "",
                "complete",
                TenantContext.currentUserId().orElse(null));
        row.setTenantId(tenantId);
        row = lookups.save(row);

        return new LookupResponse(false, outcome.query(), outcome.country(), outcome.draft(), List.of(),
                LookupResponse.MESSAGE, row.getId());
    }

    /**
     * Adds a supplier: typed in by hand, uploaded in a file's single row, or completed after
     * an honest "we found nothing" lookup. All three are one action now that the lookup no
     * longer carries a fabricated profile - only the {@code source} column and, for the
     * lookup route, the lookup row's own status, differ.
     */
    @Transactional
    SupplierProfileView add(AddSupplierRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        requireBuySeatOrDirector();

        SupplierLookupEntity lookupRow = null;
        if (request.fromLookup()) {
            lookupRow = lookups.findByTenantIdAndId(tenantId, request.lookupId())
                    .orElseThrow(() -> ApiException.notFound("Supplier lookup", request.lookupId()));
        }

        SupplierDraft draft = request.toDraft();
        String supplierKey = "own-" + draft.key().replace('|', '-');
        if (suppliers.existsByTenantIdAndSupplierKey(tenantId, supplierKey)) {
            throw ApiException.conflict("already_added", "This supplier is already on the panel.");
        }

        UUID userId = TenantContext.currentUserId().orElse(null);
        String source = request.fromLookup() ? SupplierWriter.LOOKUP_SOURCE : SupplierWriter.MANUAL_SOURCE;
        SupplierEntity saved =
                writer.create(tenantId, supplierKey, draft, userId, source, SupplierWriter.SELF_REPORTED_SOURCE);

        if (lookupRow != null) {
            lookupRow.setStatus("added");
            lookups.save(lookupRow);
        }

        // Default product coverage for a supplier the panel has not seen before, or the buy
        // panel would never offer it on anything already linked.
        suppliers.flush();
        links.linkAllForTenant(tenantId);

        // Readiness (supplier count, buy-compare status) is cached per tenant for 15 minutes;
        // without this the Data page keeps saying "no suppliers" after the first one lands.
        caches.evictAfterCommit(tenantId);
        return viewOf(tenantId, saved);
    }

    @Transactional
    SupplierProfileView patch(String supplierKey, PatchSupplierRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);

        // A profile edit goes through the same writer as an add, so the stars are re-derived
        // from whatever the new numbers are rather than left describing the old ones.
        if (request.touchesProfile()) {
            // Corrected by hand, so the rating is the buyer's own record however it first arrived.
            writer.update(s, mergedDraft(tenantId, s, request), SupplierWriter.SELF_REPORTED_SOURCE);
        }

        if (request.terms() != null) {
            SupplierTermsEntity termsRow = terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                    .orElseThrow(() -> ApiException.notFound("Supplier terms", supplierKey));
            termsRow.apply(patched(termsRow.toCommercialTerms(), request.terms()));
            terms.save(termsRow);
        }
        caches.evictAfterCommit(tenantId);
        return get(supplierKey);
    }

    @Transactional
    void delete(String supplierKey) {
        UUID tenantId = TenantContext.requireTenantId();
        SupplierEntity s = requireSupplier(tenantId, supplierKey);
        if (!s.isCustom()) {
            throw ApiException.conflict("seeded_supplier", "Only suppliers added from a lookup can be removed.");
        }
        // purchase_order.supplier_id is a business key, not a database foreign key (T7 is
        // append-only fact data, not relational to the panel) - so a supplier with purchase
        // history is protected here rather than by a constraint the database would enforce.
        if (purchaseHistory.supplier(s.getSupplierKey(), clock.today()).allTime().any()) {
            throw ApiException.conflict(
                    "supplier_has_purchases", "This supplier has purchase orders on file; it cannot be removed.");
        }
        // The FKs from terms, ratings, risk, reviews and performance months to suppliers are
        // all ON DELETE CASCADE (V8__suppliers.sql), so one delete clears the whole row.
        suppliers.delete(s);
        caches.evictAfterCommit(tenantId);
    }

    // -- Risk assembly ----------------------------------------------------------------------------

    /**
     * Builds the {@link RiskScoring.Input} from real data and hands it to the pure formula.
     * Observed (the trailing-twelve-month window has at least five received purchase orders)
     * uses {@link PurchaseHistory}; otherwise only the supplier's own provided figures count.
     */
    private SupplierRisk computeRisk(SupplierEntity s, SupplierTermsEntity termsRow, SupplierRatingEntity ratingRow,
            Map<String, PurchaseHistory.PoStats> w90BySupplierKey, LocalDate today) {
        PurchaseHistory.SupplierPurchases hist = purchaseHistory.supplier(s.getSupplierKey(), today);
        boolean observed = hist.w12().received() >= 5;

        Integer capacityUnitsMonth = termsRow == null ? null : termsRow.toCommercialTerms().capacityUnitsMonth();
        Double avgMonthlyUnits = toD(hist.w12().units());
        if (avgMonthlyUnits != null) {
            avgMonthlyUnits = avgMonthlyUnits / 12.0;
        }
        String capacity = capacityFor(avgMonthlyUnits, capacityUnitsMonth);

        RiskScoring.RatingSummary ratingSummary = ratingRow == null
                ? null
                : new RiskScoring.RatingSummary(ratingRow.getRating(), ratingRow.getReviewCount());

        RiskScoring.Input input;
        if (observed) {
            Double otif = toD(hist.w12().otifPct());
            Double avgLead = toD(hist.w12().avgLeadDays());
            Double leadSd = toD(hist.w12().leadSdDays());
            String trend = trendFrom(hist.otifTrend());
            PurchaseHistory.PoStats w90 = w90BySupplierKey.get(s.getSupplierKey());
            Double recentDelay =
                    w90 != null && w90.otifPct() != null ? 100 - w90.otifPct().doubleValue() : null;
            input = new RiskScoring.Input(
                    true, otif, s.getDefectPct(), avgLead, leadSd, trend, recentDelay, capacity, avgMonthlyUnits);
        } else {
            input = new RiskScoring.Input(
                    false, s.getOtifPct(), s.getDefectPct(), null, null, null, null, capacity, avgMonthlyUnits);
        }
        return RiskScoring.supplierRisk(input, ratingSummary, clock.now());
    }

    private Map<String, PurchaseHistory.PoStats> w90BySupplierKey(LocalDate today) {
        return purchaseHistory.bySupplier(Window.trailingDays(today, 90)).stream()
                .collect(Collectors.toMap(PurchaseHistory.SupplierPurchases::supplierKey,
                        PurchaseHistory.SupplierPurchases::w12));
    }

    /** Last-3-month OTIF vs. the prior 3, needing at least three received orders in each half. */
    private static String trendFrom(List<PurchaseHistory.MonthOtif> trend) {
        if (trend.size() < 6) {
            return null;
        }
        List<PurchaseHistory.MonthOtif> prior = trend.subList(0, 3);
        List<PurchaseHistory.MonthOtif> recent = trend.subList(3, 6);
        long priorReceived = prior.stream().mapToLong(PurchaseHistory.MonthOtif::received).sum();
        long recentReceived = recent.stream().mapToLong(PurchaseHistory.MonthOtif::received).sum();
        if (priorReceived < 3 || recentReceived < 3) {
            return null;
        }
        Double priorAvg = avgOtif(prior);
        Double recentAvg = avgOtif(recent);
        if (priorAvg == null || recentAvg == null) {
            return null;
        }
        double diff = recentAvg - priorAvg;
        return diff >= 3 ? "Improving" : diff <= -3 ? "Worsening" : "Stable";
    }

    private static Double avgOtif(List<PurchaseHistory.MonthOtif> months) {
        List<BigDecimal> present =
                months.stream().map(PurchaseHistory.MonthOtif::otifPct).filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (BigDecimal v : present) {
            sum += v.doubleValue();
        }
        return sum / present.size();
    }

    private static String capacityFor(Double avgMonthlyUnits, Integer capacityUnitsMonth) {
        if (avgMonthlyUnits == null || capacityUnitsMonth == null || capacityUnitsMonth == 0) {
            return null;
        }
        double ratio = avgMonthlyUnits / capacityUnitsMonth;
        return ratio > 0.8 ? "Low" : ratio > 0.5 ? "Medium" : "High";
    }

    private static Double toD(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    // -- Helpers ------------------------------------------------------------------------------

    private static CommercialTerms patched(CommercialTerms current, PatchSupplierRequest.TermsPatch p) {
        Integer creditDays = p.creditDays() != null ? p.creditDays() : current.creditDays();
        Double earlyPayDiscountPct =
                p.earlyPayDiscountPct() != null ? p.earlyPayDiscountPct() : current.earlyPayDiscountPct();
        Integer earlyPayDays = p.earlyPayDays() != null ? p.earlyPayDays() : current.earlyPayDays();
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

    private SupplierProfileView toView(SupplierEntity s, SupplierRatingEntity rating, SupplierTermsEntity termsRow,
            SupplierRisk risk) {
        List<String> certifications = termsRow != null ? termsRow.getCertifications() : List.of();
        Double ratingValue = rating == null ? null : rating.getRating();
        int reviewCount = rating == null ? 0 : rating.getReviewCount();
        String ratingSource = rating == null ? null : rating.getRatingSource();
        // No dims -> no stored rating row -> the panel shows "Not assessed" rather than a
        // breakdown with nothing behind it.
        RatingBreakdown breakdown = rating == null ? new RatingBreakdown(null, null, null, null) : rating.toBreakdown();
        return new SupplierProfileView(
                s.getSupplierKey(), s.getName(), s.getCountry(), s.getCity(), s.getWebsite(), s.getCategory(),
                s.getYearsTrading(), certifications, ratingValue, reviewCount, ratingSource, breakdown, List.of(),
                s.getSpendShare12m(), s.getLeadTimeDays(), s.getOtifPct(), s.getPriceIndex(), s.getDefectPct(),
                s.getHoldsStock(), s.isCustom(), s.getAddedAt(), null, null, risk.summary(), s.getCurrency());
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

    /**
     * The supplier as it would be after this patch.
     *
     * <p>Every field falls back to what is already stored, which is what makes a partial patch
     * partial: the terms panel sends one number and the form sends all of them, and both end up
     * here as a complete record. A field neither the patch nor the stored supplier has (a
     * purchases-import supplier with a figure nobody has ever supplied) stays {@code null}.
     */
    private SupplierDraft mergedDraft(UUID tenantId, SupplierEntity s, PatchSupplierRequest p) {
        String name = p.name() != null ? p.name().strip() : s.getName();
        // A country the caller did not touch is kept exactly as stored rather than
        // re-canonicalised, for the same reason the branch patch leaves an untouched
        // subdivision alone: correcting somebody's lead time should not quietly move their
        // supplier onto a different shipping lane.
        String country = p.country() != null
                ? Countries.canonicalOr(p.country(), p.country().strip())
                : s.getCountry();
        return new SupplierDraft(
                AddSupplierRequest.key(name, country),
                name,
                country,
                p.city() != null ? p.city().strip() : s.getCity(),
                p.website() != null ? p.website().strip() : s.getWebsite(),
                p.category() != null ? p.category().strip() : s.getCategory(),
                p.yearsTrading() != null ? p.yearsTrading() : s.getYearsTrading(),
                p.certifications() != null ? p.certifications() : currentCertifications(tenantId, s),
                p.leadTimeDays() != null ? p.leadTimeDays() : s.getLeadTimeDays(),
                p.otifPct() != null ? p.otifPct() : s.getOtifPct(),
                p.priceIndex() != null ? p.priceIndex() : s.getPriceIndex(),
                p.defectPct() != null ? p.defectPct() : s.getDefectPct(),
                p.holdsStock() != null ? p.holdsStock() : s.getHoldsStock(),
                // Not stored on the supplier: it is a star, and the stars live on the rating row.
                p.communication() != null ? p.communication() : SupplierDraft.DEFAULT_COMMUNICATION,
                p.contactName() != null ? p.contactName().strip() : s.getContactName(),
                p.resolvedEmail() != null ? p.resolvedEmail().strip() : s.getEmail(),
                "",
                0);
    }

    /** Certifications live on the terms row, and a patch that does not mention them keeps them. */
    private List<String> currentCertifications(UUID tenantId, SupplierEntity s) {
        return terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                .map(SupplierTermsEntity::getCertifications)
                .orElseGet(List::of);
    }

    /**
     * Puts a list of suppliers on the panel.
     *
     * <p>Row by row rather than all or nothing, which is the opposite of the CSV loader and
     * deliberate. A sales history half-imported is unusable because nobody can tell which
     * months are complete; a supplier panel is a list of independent companies, and refusing
     * forty-nine of them because the fiftieth has a bad country would be losing work the buyer
     * has already done. Each rejection is returned with its index so the screen can point at
     * the row.
     *
     * <p>Each row is still validated here. What the browser checked was the file's shape;
     * only the server knows the panel, and only the server decides what a supplier may claim
     * about itself.
     */
    @Transactional
    ImportSuppliersResponse importSuppliers(ImportSuppliersRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        requireBuySeatOrDirector();
        UUID userId = TenantContext.currentUserId().orElse(null);

        List<SupplierProfileView> added = new ArrayList<>();
        List<SupplierProfileView> updated = new ArrayList<>();
        List<ImportSuppliersResponse.Rejection> rejected = new ArrayList<>();

        // Within one file the later row wins, matching how the client de-duplicates its own
        // preview: a corrected line appended to an export is meant to replace what came before.
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < request.suppliers().size(); index++) {
            AddSupplierRequest row = request.suppliers().get(index);
            try {
                if (row.fromLookup()) {
                    throw ApiException.badRequest("lookup_not_importable",
                            "An import carries supplier records, not lookup ids.");
                }
                // The same completeness rule bean validation applies to a single add, checked
                // here so a bad row is rejected on its own rather than failing the whole file.
                if (!row.isUsable()) {
                    throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "incomplete_supplier",
                            "A supplier needs a name, a country, a lead time and an on-time rate.",
                            java.util.Map.of("field", row.name() == null || row.name().isBlank() ? "name" : "country"));
                }
                SupplierDraft draft = row.toDraft();
                String supplierKey = "own-" + draft.key().replace('|', '-');

                Optional<SupplierEntity> existing = suppliers.findByTenantIdAndSupplierKey(tenantId, supplierKey);
                // Counted as an update if the panel already had it, or if an earlier row in
                // this same file did - otherwise a corrected duplicate would report as "added".
                boolean existed = existing.isPresent() | !seen.add(supplierKey);

                SupplierEntity saved = existing
                        .map(found -> writer.update(found, draft, SupplierWriter.IMPORTED_SOURCE))
                        .orElseGet(() -> writer.create(tenantId, supplierKey, draft, userId,
                                SupplierWriter.IMPORT_SOURCE, SupplierWriter.IMPORTED_SOURCE));

                (existed ? updated : added).add(viewOf(tenantId, saved));
            } catch (ApiException ex) {
                rejected.add(new ImportSuppliersResponse.Rejection(
                        index, row.name() == null ? "" : row.name(), ex.getMessage(), fieldOf(ex)));
            }
        }

        // Give anything new the default product coverage, or it can quote on nothing:
        // SupplierGateway.panelFor falls back to the whole panel only for an item with no
        // links at all, so on a tenant whose catalogue is already linked a new supplier would
        // be invisible on every product. See SupplierProductLinkSeeder.
        if (!added.isEmpty()) {
            suppliers.flush();
            links.linkAllForTenant(tenantId);
        }
        if (!added.isEmpty() || !updated.isEmpty()) {
            caches.evictAfterCommit(tenantId);
        }

        return new ImportSuppliersResponse(added, updated, rejected);
    }

    /** The field an error names, when it names one. */
    private static String fieldOf(ApiException ex) {
        Object field = ex.details().get("field");
        return field == null ? null : field.toString();
    }

    /** A saved supplier with the rows that hang off it, as the panel shows it. */
    private SupplierProfileView viewOf(UUID tenantId, SupplierEntity saved) {
        SupplierRatingEntity rating = ratings.findById(saved.getId()).orElse(null);
        SupplierTermsEntity termsRow = terms.findById(saved.getId()).orElse(null);
        LocalDate today = clock.today();
        SupplierRisk risk = computeRisk(saved, termsRow, rating, w90BySupplierKey(today), today);
        return toView(saved, rating, termsRow, risk);
    }
}

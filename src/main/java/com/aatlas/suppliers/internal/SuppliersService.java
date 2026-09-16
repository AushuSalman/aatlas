package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.seed.Seeded;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.aatlas.suppliers.internal.csv.SupplierDraft;
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
import java.util.Optional;
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
    private final SupplierWriter writer;
    private final SupplierProductLinkSeeder links;

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
            PolicyReader policy,
            SupplierWriter writer,
            SupplierProductLinkSeeder links) {
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
        this.writer = writer;
        this.links = links;
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

        // Two shapes, one action. A record typed in by hand goes through the same writer the
        // CSV import uses, so a supplier described in the form and the same supplier uploaded
        // in a file produce identical rows - including a rating derived the same way.
        if (!request.fromLookup()) {
            return addFromRecord(tenantId, request);
        }

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

        // As above: a supplier added from a web lookup gets the same default coverage, or the
        // buy panel would never offer it on anything already linked.
        suppliers.flush();
        links.linkAllForTenant(tenantId);

        return toView(supplier, ratingRow, termsRow, reviewRows, riskRow);
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
    /**
     * Adds a supplier the buyer described themselves.
     *
     * <p>Keyed on name and country, the same key the CSV import de-duplicates by, so adding a
     * supplier that a file already brought in corrects it rather than creating a second row.
     */
    private SupplierProfileView addFromRecord(UUID tenantId, AddSupplierRequest request) {
        SupplierDraft draft = request.toDraft();
        String supplierKey = "own-" + draft.key().replace('|', '-');
        UUID userId = TenantContext.currentUserId().orElse(null);

        SupplierEntity saved = suppliers.findByTenantIdAndSupplierKey(tenantId, supplierKey)
                .map(existing -> writer.update(existing, draft, SupplierWriter.SELF_REPORTED_SOURCE))
                .orElseGet(() -> writer.create(
                        tenantId, supplierKey, draft, userId, SupplierWriter.SELF_REPORTED_SOURCE));

        // Default product coverage for a supplier the panel has not seen before - see the note
        // in importSuppliers. Idempotent, so re-saving an existing supplier adds nothing.
        suppliers.flush();
        links.linkAllForTenant(tenantId);

        return toView(
                saved,
                ratings.findById(saved.getId()).orElseThrow(),
                terms.findById(saved.getId()).orElseThrow(),
                reviews.findByTenantIdAndSupplierIdOrderByPositionAsc(tenantId, saved.getId()),
                risks.findById(saved.getId()).orElseThrow());
    }
    /**
     * The supplier as it would be after this patch.
     *
     * <p>Every field falls back to what is already stored, which is what makes a partial patch
     * partial: the terms panel sends one number and the form sends all of them, and both end up
     * here as a complete record.
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
                p.holdsStock() != null ? p.holdsStock() : s.isHoldsStock(),
                // Not stored on the supplier: it is a star, and the stars live on the rating row.
                p.communication() != null ? p.communication() : SupplierDraft.DEFAULT_COMMUNICATION,
                p.contactName() != null ? p.contactName().strip() : s.getContactName(),
                p.resolvedEmail() != null ? p.resolvedEmail().strip() : s.getEmail(),
                "",
                0);
    }

    /** Certifications live on the terms row, and a patch that does not mention them keeps them. */
    private java.util.List<String> currentCertifications(UUID tenantId, SupplierEntity s) {
        return terms.findBySupplierIdAndTenantId(s.getId(), tenantId)
                .map(SupplierTermsEntity::getCertifications)
                .orElseGet(java.util.List::of);
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
                        .orElseGet(() -> writer.create(
                                tenantId, supplierKey, draft, userId, SupplierWriter.IMPORTED_SOURCE));

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

        return new ImportSuppliersResponse(added, updated, rejected);
    }

    /** The field an error names, when it names one. */
    private static String fieldOf(ApiException ex) {
        Object field = ex.details().get("field");
        return field == null ? null : field.toString();
    }

    /** A saved supplier with the rows that hang off it, as the panel shows it. */
    private SupplierProfileView viewOf(UUID tenantId, SupplierEntity saved) {
        return toView(
                saved,
                ratings.findById(saved.getId()).orElseThrow(),
                terms.findById(saved.getId()).orElseThrow(),
                reviews.findByTenantIdAndSupplierIdOrderByPositionAsc(tenantId, saved.getId()),
                risks.findById(saved.getId()).orElseThrow());
    }
}

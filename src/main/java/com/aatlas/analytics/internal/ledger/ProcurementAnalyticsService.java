package com.aatlas.analytics.internal.ledger;

import com.aatlas.analytics.BuyImpactSummary;
import com.aatlas.analytics.ProcurementAnalytics;
import com.aatlas.analytics.ProcurementLedger;
import com.aatlas.analytics.PurchaseOrderRecord;
import com.aatlas.analytics.RecordAward;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.history.Catalogue;
import com.aatlas.history.HistoryCaches;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
import com.aatlas.history.Suppliers;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The procurement ledger, read as the Buying insights dashboard reads it - one reduction of
 * {@code purchase_order} per request, the read-side mirror of
 * {@code platform/procurement.ts}'s {@code computeBuyAnalytics} and friends.
 */
@Service
@Transactional(readOnly = true)
public class ProcurementAnalyticsService implements ProcurementAnalytics, ProcurementLedger {

    private final PurchaseOrderRepository repository;
    private final AatlasClock clock;
    private final Catalogue catalogue;
    private final Suppliers suppliers;
    private final Reference reference;
    private final PurchaseHistory purchaseHistory;
    private final HistoryCaches caches;

    ProcurementAnalyticsService(PurchaseOrderRepository repository, AatlasClock clock, Catalogue catalogue,
            Suppliers suppliers, Reference reference, PurchaseHistory purchaseHistory, HistoryCaches caches) {
        this.repository = repository;
        this.clock = clock;
        this.catalogue = catalogue;
        this.suppliers = suppliers;
        this.reference = reference;
        this.purchaseHistory = purchaseHistory;
        this.caches = caches;
    }

    private List<PoRow> ledger(UUID tenantId) {
        List<PoRow> rows = repository.findByTenantIdOrderByOrderDateDescSeqAsc(tenantId).stream()
                .map(PurchaseOrderEntity::toRow)
                .toList();
        if (rows.isEmpty()) {
            throw noLedger();
        }
        return rows;
    }

    /**
     * The supplier panel's own facts for every supplier id the ledger's rows carry, one lookup
     * per distinct supplier (a handful, never per row) - {@link SupplierFacts#EMPTY} for a
     * supplier id the panel no longer has (should not happen, guarded rather than assumed).
     */
    private Map<String, SupplierFacts> supplierFacts(List<PoRow> rows) {
        Map<String, SupplierFacts> out = new LinkedHashMap<>();
        for (String id : rows.stream().map(PoRow::supplierId).distinct().toList()) {
            SupplierFacts facts = suppliers.supplier(id)
                    .map(ref -> new SupplierFacts(ref.vendorCode(), ref.priceIndex(), ref.leadTimeDays(),
                            suppliers.terms(id).map(Suppliers.Terms::qualityPpm).orElse(null),
                            suppliers.terms(id).map(Suppliers.Terms::creditDays).orElse(null)))
                    .orElse(SupplierFacts.EMPTY);
            out.put(id, facts);
        }
        return out;
    }

    private static ApiException noLedger() {
        return new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "no_ledger",
                "This workspace has no procurement history yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen.");
    }

    DateRange resolveRange(String rangeKey, LocalDate from, LocalDate to) {
        LocalDate today = clock.today();
        if (rangeKey != null && !rangeKey.isBlank() && !"custom".equals(rangeKey)) {
            return DateRanges.resolveRange(rangeKey, today);
        }
        if (from != null || to != null) {
            return DateRanges.resolveRange("custom", today, from, to);
        }
        return DateRanges.resolveRange("30d", today);
    }

    private static Filters filters(String branch, String category) {
        return new Filters(
                branch == null || branch.isBlank() ? Filters.ALL : branch,
                category == null || category.isBlank() ? Filters.ALL : category);
    }

    public BuyAnalytics analyze(String rangeKey, LocalDate from, LocalDate to, String branch, String category) {
        UUID tenantId = TenantContext.requireTenantId();
        DateRange range = resolveRange(rangeKey, from, to);
        List<PoRow> rows = ledger(tenantId);
        return BuyAnalyticsEngine.compute(range, filters(branch, category), rows, supplierFacts(rows));
    }

    public List<MixSlice> mix(String dimension, String rangeKey, LocalDate from, LocalDate to, String branch, String category) {
        UUID tenantId = TenantContext.requireTenantId();
        DateRange range = resolveRange(rangeKey, from, to);
        Filters f = filters(branch, category);
        List<PoRow> allRows = ledger(tenantId);
        String dim = dimension == null ? "supplier" : dimension.toLowerCase(Locale.ROOT).strip();
        if ("branch".equals(dim)) {
            return BuyAnalyticsEngine.branchMix(range, f, allRows);
        }
        Map<String, SupplierFacts> facts = supplierFacts(allRows);
        return switch (dim) {
            case "category" -> BuyAnalyticsEngine.compute(range, f, allRows, facts).categoryMix();
            case "region" -> BuyAnalyticsEngine.compute(range, f, allRows, facts).regionMix();
            case "origin" -> BuyAnalyticsEngine.compute(range, f, allRows, facts).originMix();
            case "supplier" -> BuyAnalyticsEngine.compute(range, f, allRows, facts).supplierMix();
            default -> throw ApiException.badRequest("invalid_dimension",
                    "dimension must be one of supplier, category, branch, region, origin.");
        };
    }

    public record RangesResponse(List<DateRanges.RangePreset> ranges, LocalDate earliestOrder) {
    }

    public RangesResponse ranges() {
        UUID tenantId = TenantContext.requireTenantId();
        List<PoRow> rows = ledger(tenantId);
        return new RangesResponse(DateRanges.PRESETS, BuyAnalyticsEngine.ledgerStart(rows));
    }

    public CursorPage<PoRow> searchLedger(String q, String status, int limit, String cursor) {
        UUID tenantId = TenantContext.requireTenantId();
        LedgerCursor after = LedgerCursor.decode(cursor);

        Specification<PurchaseOrderEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("itemNumber")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\'),
                        cb.like(cb.lower(root.get("supplierName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("poNumber")), pattern, '\\'),
                        cb.like(cb.lower(root.get("branchName")), pattern, '\\')));
            }
            if (status != null && !status.isBlank()) {
                where.add(cb.equal(root.get("status"), status.strip()));
            }
            if (after != null) {
                where.add(cb.or(
                        cb.lessThan(root.get("orderDate"), after.date()),
                        cb.and(cb.equal(root.get("orderDate"), after.date()), cb.greaterThan(root.get("seq"), after.seq()))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<PurchaseOrderEntity> rows = repository.findBy(spec, fetch -> fetch
                .sortBy(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("orderDate"),
                        org.springframework.data.domain.Sort.Order.asc("seq")))
                .limit(limit + 1)
                .all());

        List<PoRow> page = rows.stream().map(PurchaseOrderEntity::toRow).toList();
        return CursorPage.of(page, limit, row -> new LedgerCursor(row.date(), row.seq()).encode());
    }

    // -- ProcurementAnalytics (public API) -------------------------------------------------

    @Override
    public BuyImpactSummary trailingTwelveMonthImpact() {
        UUID tenantId = TenantContext.requireTenantId();
        if (!repository.existsByTenantId(tenantId)) {
            return new BuyImpactSummary(0, 0, 0, 0, List.of());
        }
        DateRange range = DateRanges.resolveRange("12m", clock.today());
        List<PoRow> rows = ledger(tenantId);
        BuyAnalytics a = BuyAnalyticsEngine.compute(range, Filters.none(), rows, supplierFacts(rows));
        List<BuyImpactSummary.MonthPoint> byMonth = a.timeline().stream()
                .map(t -> new BuyImpactSummary.MonthPoint(t.bucket().label(), t.saved(), t.leaked()))
                .toList();
        return new BuyImpactSummary(a.lines(), a.captureRate().value(), a.saved().value(), a.leaked().value(), byMonth);
    }

    // -- ProcurementLedger (public API) -----------------------------------------------------

    @Override
    @Transactional
    public PurchaseOrderRecord recordAward(RecordAward award) {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate today = clock.today();
        LocalDate orderDate = award.orderDate() != null ? award.orderDate() : today;

        double exWorks = award.exWorks().doubleValue();
        double freight = award.freight().doubleValue();
        double duty = award.duty().doubleValue();
        double landed = exWorks + freight + duty;
        double baseline = award.baseline() != null ? award.baseline().doubleValue() : landed;
        double target = award.target() != null ? award.target().doubleValue() : landed;
        double spend = landed * award.qty();

        Optional<Catalogue.StoreRef> store = catalogue.store(award.branchId());
        String regionKey = store.map(Catalogue.StoreRef::regionKey).orElse("unassigned");
        String regionLabel = "unassigned".equals(regionKey)
                ? "Needs a region"
                : catalogue.region(regionKey).map(Catalogue.RegionRef::label).orElse(regionKey);
        String branchName = store.map(Catalogue.StoreRef::legalName).orElse(award.branchId());

        // Promised days: the supplier's own known lead time, else what has actually been
        // observed from them, plus the reference transit time for their country - never a
        // fabricated number; absent when neither the file nor history says anything.
        Integer supplierLeadDays = suppliers.supplier(award.supplierId())
                .map(Suppliers.SupplierRef::leadTimeDays).orElse(null);
        if (supplierLeadDays == null) {
            BigDecimal observed = purchaseHistory.supplier(award.supplierId(), today).allTime().avgLeadDays();
            supplierLeadDays = observed == null ? null : observed.setScale(0, RoundingMode.HALF_UP).intValue();
        }
        Reference.Origin origin = reference.origin(award.country());
        Integer promisedDays = supplierLeadDays == null ? null : supplierLeadDays + origin.inboundDays();
        LocalDate promisedDate = promisedDays == null ? null : orderDate.plusDays(promisedDays);

        String category = award.category() != null && !award.category().isBlank()
                ? award.category()
                : catalogue.product(award.itemNumber()).map(Catalogue.ProductRef::category)
                        .orElse(Categories.UNCATEGORISED);

        int seq = (int) Math.min(Integer.MAX_VALUE, repository.countByTenantId(tenantId) + 1_000_000L);
        String poNumber = "PO-AWD-" + orderDate.toString().replace("-", "") + "-" + seq;

        PoRow row = new PoRow(
                seq, poNumber, orderDate, award.supplierId(), award.supplierName(), award.country(),
                award.itemNumber(), award.description() == null ? award.itemNumber() : award.description(), category,
                award.branchId(), branchName,
                regionKey, regionLabel, award.qty(),
                exWorks, freight, duty, landed, baseline, target, landed <= target + 0.005,
                spend, baseline * award.qty(), Math.max(0, baseline - landed) * award.qty(),
                Math.max(0, landed - target) * award.qty(),
                "open", promisedDays, null, null, null, promisedDate, null, poNumber, "award");

        PurchaseOrderEntity entity = new PurchaseOrderEntity(row);
        entity.setTenantId(tenantId);
        entity.setDecisionId(award.decisionId());
        if (store.isPresent()) {
            entity.setStoreId(store.get().id());
        }
        Optional<Catalogue.ProductRef> product = catalogue.product(award.itemNumber());
        if (product.isPresent()) {
            entity.setProductId(product.get().id());
        }
        entity = repository.save(entity);

        caches.evictAfterCommit(tenantId);

        return new PurchaseOrderRecord(entity.getId(), entity.getPoNumber(), entity.getOrderDate(),
                BigDecimal.valueOf(landed), BigDecimal.valueOf(spend));
    }
}

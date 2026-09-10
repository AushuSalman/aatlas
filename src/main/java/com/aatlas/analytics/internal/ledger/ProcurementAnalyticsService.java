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
import com.aatlas.analytics.internal.fixtures.Fixtures;
import com.aatlas.analytics.internal.fixtures.Lane;
import com.aatlas.analytics.internal.fixtures.RegionFixture;
import com.aatlas.analytics.internal.fixtures.SupplierFixture;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    ProcurementAnalyticsService(PurchaseOrderRepository repository, AatlasClock clock) {
        this.repository = repository;
        this.clock = clock;
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
        return BuyAnalyticsEngine.compute(range, filters(branch, category), ledger(tenantId));
    }

    public List<MixSlice> mix(String dimension, String rangeKey, LocalDate from, LocalDate to, String branch, String category) {
        UUID tenantId = TenantContext.requireTenantId();
        DateRange range = resolveRange(rangeKey, from, to);
        Filters f = filters(branch, category);
        List<PoRow> allRows = ledger(tenantId);
        String dim = dimension == null ? "supplier" : dimension.toLowerCase(Locale.ROOT).strip();
        return switch (dim) {
            case "category" -> BuyAnalyticsEngine.compute(range, f, allRows).categoryMix();
            case "branch" -> BuyAnalyticsEngine.branchMix(range, f, allRows);
            case "region" -> BuyAnalyticsEngine.compute(range, f, allRows).regionMix();
            case "origin" -> BuyAnalyticsEngine.compute(range, f, allRows).originMix();
            case "supplier" -> BuyAnalyticsEngine.compute(range, f, allRows).supplierMix();
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
        BuyAnalytics a = BuyAnalyticsEngine.compute(range, Filters.none(), ledger(tenantId));
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
        LocalDate orderDate = award.orderDate() != null ? award.orderDate() : clock.today();

        double exWorks = award.exWorks().doubleValue();
        double freight = award.freight().doubleValue();
        double duty = award.duty().doubleValue();
        double landed = exWorks + freight + duty;
        double baseline = award.baseline() != null ? award.baseline().doubleValue() : landed;
        double target = award.target() != null ? award.target().doubleValue() : landed;
        double spend = landed * award.qty();

        RegionFixture region = Fixtures.regionForStore(award.branchId());
        Lane lane = Fixtures.laneFor(award.country(), region);
        int promisedDays = 0;
        SupplierFixture supplier = Fixtures.findSupplier(award.supplierId());
        if (supplier != null) {
            promisedDays = supplier.leadTimeDays() + lane.transitDays();
        }

        String category = award.category() != null && !award.category().isBlank()
                ? award.category()
                : Categories.categoryOf(award.itemNumber());

        int seq = (int) Math.min(Integer.MAX_VALUE, repository.countByTenantId(tenantId) + 1_000_000L);
        String poNumber = "PO-AWD-" + orderDate.toString().replace("-", "") + "-" + seq;

        PoRow row = new PoRow(
                seq, poNumber, orderDate, award.supplierId(), award.supplierName(), award.country(),
                award.itemNumber(), award.description() == null ? award.itemNumber() : award.description(), category,
                award.branchId(), Fixtures.storeName(award.branchId()),
                region.key(), region.label(), award.qty(),
                exWorks, freight, duty, landed, baseline, target, landed <= target + 0.005,
                spend, baseline * award.qty(), Math.max(0, baseline - landed) * award.qty(),
                Math.max(0, landed - target) * award.qty(),
                "open", promisedDays, 0, 0, true, orderDate.plusDays(promisedDays), null);

        PurchaseOrderEntity entity = new PurchaseOrderEntity(row);
        entity.setTenantId(tenantId);
        entity.setDecisionId(award.decisionId());
        entity = repository.save(entity);

        return new PurchaseOrderRecord(entity.getId(), entity.getPoNumber(), entity.getOrderDate(),
                BigDecimal.valueOf(landed), BigDecimal.valueOf(spend));
    }
}

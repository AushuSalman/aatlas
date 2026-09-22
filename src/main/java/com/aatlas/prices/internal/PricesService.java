package com.aatlas.prices.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DecisionKind;
import com.aatlas.decisions.DecisionRecorder;
import com.aatlas.decisions.RecordDecisionRequest;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.HistoryCaches;
import com.aatlas.history.PriceBook;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PriceList;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.Window;
import com.aatlas.history.PricingMath;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The prices workflow: suggestions from the history read layer, writes through
 * {@link PriceBook}, and the one JDBC read this module keeps for itself - the audit trail of
 * a pair's price rows, which no history contract exposes.
 *
 * <p>The seat gate reads {@code role_policy} directly: the {@code prices} module may depend
 * only on {@code common}, {@code decisions} and {@code history}, so it mirrors what the
 * policy module would answer rather than importing it.
 */
@Service
class PricesService {

    private static final Logger log = LoggerFactory.getLogger(PricesService.class);

    static final int MAX_ROWS = 2000;
    static final String SCOPE_MISSING = "missing";
    static final String SCOPE_ALL = "all";
    static final List<String> WRITE_SOURCES = List.of("wizard", "manual");
    static final String TENANT_WIDE = "Tenant-wide";

    private static final BigDecimal MIN_MARGIN_OVERRIDE = BigDecimal.ONE;
    private static final BigDecimal MAX_MARGIN_OVERRIDE = BigDecimal.valueOf(90);
    /** How many item numbers a decision's detail names before "and N more". */
    private static final int DETAIL_ITEMS = 5;

    private final Catalogue catalogue;
    private final PriceLadder ladder;
    private final PriceList priceList;
    private final PriceBook priceBook;
    private final SalesHistory sales;
    private final CompetitorPrices competitors;
    private final Reference reference;
    private final DecisionRecorder ledger;
    private final HistoryCaches caches;
    private final AatlasClock clock;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate requiresNew;

    PricesService(Catalogue catalogue, PriceLadder ladder, PriceList priceList, PriceBook priceBook,
            SalesHistory sales, CompetitorPrices competitors, Reference reference, DecisionRecorder ledger,
            HistoryCaches caches, AatlasClock clock, JdbcTemplate jdbc, ObjectMapper json,
            PlatformTransactionManager transactions) {
        this.catalogue = catalogue;
        this.ladder = ladder;
        this.priceList = priceList;
        this.priceBook = priceBook;
        this.sales = sales;
        this.competitors = competitors;
        this.reference = reference;
        this.ledger = ledger;
        this.caches = caches;
        this.clock = clock;
        this.jdbc = jdbc;
        this.json = json;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- suggestions ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    SuggestionsResponse suggestions(String scope, String storeCode, String margins) {
        String wanted = scope == null || scope.isBlank() ? SCOPE_MISSING : scope.strip().toLowerCase(Locale.ROOT);
        if (!SCOPE_MISSING.equals(wanted) && !SCOPE_ALL.equals(wanted)) {
            throw ApiException.badRequest("invalid_scope", "scope must be 'missing' or 'all'.");
        }
        Catalogue.StoreRef store = storeCode == null || storeCode.isBlank() ? null
                : catalogue.store(storeCode).orElseThrow(() -> ApiException.notFound("Store", storeCode));
        UUID storeId = store == null ? null : store.id();
        String region = store == null || SalesHistory.GroupStats.UNASSIGNED.equals(store.regionKey()) ? null
                : store.regionKey();
        Map<String, BigDecimal> overrides = parseMargins(margins);
        LocalDate today = clock.today();
        Window w12 = Window.trailingMonths(today, 12);
        String currency = currency();

        Map<UUID, Resolved> costs = ladder.costs(storeId, today);
        Map<UUID, Resolved> currents = ladder.currentPrices(storeId, today);
        Map<UUID, SalesHistory.PriceBand> bands = sales.priceBands(w12);
        Map<UUID, com.aatlas.history.Anchor> medians = competitors.medians(today);

        List<SuggestionEngine.SuggestionRow> rows = new ArrayList<>();
        Map<String, CategorySummary> categories = new LinkedHashMap<>();
        boolean truncated = false;
        int priceable = 0;
        int missingCost = 0;
        for (Catalogue.ProductRef product : catalogue.products()) {
            Resolved current = currents.get(product.id());
            if (SCOPE_MISSING.equals(wanted) && current != null) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }
            Reference.Benchmark benchmark = reference.benchmark(product.category(), product.subcategory());
            BigDecimal override = overrides.get(key(product.category()));
            List<CompetitorPrices.Observation> observations = medians.containsKey(product.id())
                    ? competitors.forItem(product.id(), region, storeId, today) : List.of();
            SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                    product, store, costs.get(product.id()), current, observations, bands.get(product.id()),
                    benchmark, override, reference.commodity(product.commodity()), currency));
            rows.add(row);
            if (row.unpriceable()) {
                missingCost++;
            } else {
                priceable++;
            }
            categories.compute(product.category(), (category, existing) -> {
                if (existing != null) {
                    return existing.plusOne();
                }
                Reference.Benchmark categoryBand = reference.benchmark(category, null);
                BigDecimal target = override != null ? override : categoryBand.targetMarginPct();
                return new CategorySummary(category, target, categoryBand.targetMarginPct(),
                        categoryBand.lowMarginPct(), categoryBand.highMarginPct(), categoryBand.note(), 1,
                        override != null);
            });
        }
        BigDecimal regionalIndex = store == null || store.rpp() == null ? BigDecimal.ONE.setScale(3)
                : BigDecimal.ONE.subtract(store.rpp().subtract(PricingMath.HUNDRED)
                        .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP)
                        .multiply(SuggestionEngine.RPP_SENSITIVITY)).setScale(3, RoundingMode.HALF_UP);
        return new SuggestionsResponse(wanted, store == null ? null : store.storeCode(), currency, regionalIndex,
                List.copyOf(categories.values()), rows,
                new SuggestionsResponse.Summary(rows.size(), priceable, rows.size() - priceable, missingCost),
                truncated);
    }

    /** {@code Plumbing:34,HVAC:28} → category → target margin, clamped to 1-90. */
    static Map<String, BigDecimal> parseMargins(String margins) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (margins == null || margins.isBlank()) {
            return out;
        }
        for (String part : margins.split(",")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0 || colon == part.length() - 1) {
                throw ApiException.badRequest("invalid_margins",
                        "margins must be 'Category:pct,Category:pct'; got '" + part.strip() + "'.");
            }
            String category = part.substring(0, colon).strip();
            BigDecimal pct;
            try {
                pct = new BigDecimal(part.substring(colon + 1).strip());
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("invalid_margins", "'" + part.strip() + "' is not a margin.");
            }
            out.put(key(category), PricingMath.clamp(pct, MIN_MARGIN_OVERRIDE, MAX_MARGIN_OVERRIDE));
        }
        return out;
    }

    // ---- writes --------------------------------------------------------------------------

    @Transactional
    BulkPriceResponse bulk(BulkPriceRequest request) {
        requireSellSeat();
        String source = request.source() == null || request.source().isBlank() ? "wizard"
                : request.source().strip().toLowerCase(Locale.ROOT);
        if (!WRITE_SOURCES.contains(source)) {
            throw ApiException.badRequest("invalid_source", "source must be one of " + WRITE_SOURCES + ".");
        }
        if (request.rows().size() > MAX_ROWS) {
            throw ApiException.badRequest("too_many_rows", "At most " + MAX_ROWS + " rows per call.");
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null ? clock.today() : request.effectiveFrom();
        Map<String, Object> fields = new LinkedHashMap<>();
        List<PriceBook.PriceWrite> writes = new ArrayList<>();
        for (int i = 0; i < request.rows().size(); i++) {
            BulkPriceRequest.Row row = request.rows().get(i);
            validateFigures(row.listPrice(), row.cost(), "rows[" + i + "]", fields);
            writes.add(new PriceBook.PriceWrite(row.item(), blankToNull(row.store()), row.listPrice(), row.cost(),
                    effectiveFrom, row.basis()));
        }
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "The request body is not valid.",
                    Map.of("fields", fields));
        }
        PriceBook.WriteResult result = priceBook.write(writes, source, TenantContext.currentUserId().orElse(null), null);
        if (result.written() > 0) {
            String branch = branchName(writes.stream().map(PriceBook.PriceWrite::storeCode).toList());
            recordDecision("Set " + prices(result.written()) + " with the pricing wizard"
                    + (branch == null ? "" : " at " + branch), branch, result.written(),
                    detail(writtenItems(writes, result.skipped())));
        }
        return new BulkPriceResponse(result.writeId(), result.written(), result.skipped(), effectiveFrom);
    }

    @Transactional
    UndoResponse undo(UUID writeId) {
        requireSellSeat();
        // Read before the delete: afterwards the rows that named the branch are gone.
        String branch = branchName(jdbc.queryForList("""
                SELECT s.store_code FROM product_prices pp LEFT JOIN stores s ON s.id = pp.store_id
                 WHERE pp.tenant_id = ? AND pp.write_id = ?
                """, String.class, TenantContext.requireTenantId(), writeId));
        PriceBook.UndoResult result = priceBook.undo(writeId);
        if (result.deleted() > 0) {
            recordDecision("Undid " + prices(result.deleted()), branch, result.deleted(),
                    "Pricing wizard write " + writeId + " undone");
        }
        return new UndoResponse(result.deleted(), result.kept());
    }

    /**
     * One bulk-sell decision per wizard write, so History and the Overview show what was set.
     * A price-list write is not a sale, so no deal row goes with it.
     *
     * <p>Recorded once the write has committed, in a transaction of its own: the recorder is
     * transactional too, so a failure inside a joined transaction would mark the write
     * rollback-only however it was caught, and the user would get a 500 with nothing written.
     * This way a ledger problem is logged and the prices stay.
     */
    private void recordDecision(String title, String branchOrNull, int count, String detail) {
        RecordDecisionRequest request = new RecordDecisionRequest(DecisionKind.BULK_SELL, title, null,
                branchOrNull == null ? TENANT_WIDE : branchOrNull, null, null, BigDecimal.ZERO, "/month", detail,
                count, null);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            recordNow(request);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recordNow(request);
            }
        });
    }

    private void recordNow(RecordDecisionRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        try {
            requiresNew.executeWithoutResult(status -> {
                ledger.record(request);
                // The write's own eviction ran before this row existed; the Overview lists decisions.
                caches.evictAfterCommit(tenantId);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not record the price write as a decision ({}): {}", request.title(), ex.toString());
        }
    }

    /**
     * The one branch's legal name when every row named the same branch; null for tenant-wide
     * or mixed. A blank code is a scope of its own (tenant-wide), so one tenant-wide row among
     * branch rows makes the write mixed rather than that branch's.
     */
    private String branchName(List<String> storeCodes) {
        Set<String> codes = new LinkedHashSet<>();
        for (String code : storeCodes) {
            codes.add(code == null ? "" : code.strip());
        }
        if (codes.size() != 1) {
            return null;
        }
        String code = codes.iterator().next();
        if (code.isEmpty()) {
            return null;
        }
        return catalogue.store(code).map(Catalogue.StoreRef::legalName).orElse("Branch " + code);
    }

    private static List<String> writtenItems(List<PriceBook.PriceWrite> writes, List<PriceBook.Skipped> skipped) {
        Set<String> skippedKeys = new LinkedHashSet<>();
        for (PriceBook.Skipped s : skipped) {
            skippedKeys.add(s.itemNumber() + "@" + s.storeCode());
        }
        Set<String> items = new LinkedHashSet<>();
        for (PriceBook.PriceWrite w : writes) {
            if (!skippedKeys.contains(w.itemNumber() + "@" + w.storeCode())) {
                items.add(w.itemNumber());
            }
        }
        return List.copyOf(items);
    }

    /** {@code "HRD118902, HRD118903 and 11 more"}. */
    private static String detail(List<String> items) {
        String head = String.join(", ", items.subList(0, Math.min(items.size(), DETAIL_ITEMS)));
        int more = items.size() - DETAIL_ITEMS;
        return more > 0 ? head + " and " + more + " more" : head;
    }

    private static String prices(int n) {
        return n + (n == 1 ? " price" : " prices");
    }

    @Transactional
    PriceDetail set(String item, SetPriceRequest request) {
        requireSellSeat();
        Catalogue.ProductRef product = catalogue.product(item).orElseThrow(() -> ApiException.notFound("Item", item));
        String storeCode = blankToNull(request.store());
        if (storeCode != null) {
            catalogue.store(storeCode).orElseThrow(() -> ApiException.notFound("Store", storeCode));
        }
        String source = request.source() == null || request.source().isBlank() ? "manual"
                : request.source().strip().toLowerCase(Locale.ROOT);
        if (!WRITE_SOURCES.contains(source)) {
            throw ApiException.badRequest("invalid_source", "source must be one of " + WRITE_SOURCES + ".");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        validateFigures(request.listPrice(), request.cost(), "", fields);
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "The request body is not valid.",
                    Map.of("fields", fields));
        }
        LocalDate effectiveFrom = request.effectiveFrom() == null ? clock.today() : request.effectiveFrom();
        PriceBook.WriteResult result = priceBook.write(List.of(new PriceBook.PriceWrite(product.itemNumber(), storeCode,
                request.listPrice(), request.cost(), effectiveFrom, request.basis())), source,
                TenantContext.currentUserId().orElse(null), null);
        if (result.written() == 0) {
            PriceBook.Skipped skipped = result.skipped().get(0);
            throw ApiException.badRequest(skipped.reason(), "The price could not be written: " + skipped.reason() + ".");
        }
        return detail(product.itemNumber(), storeCode);
    }

    private static void validateFigures(BigDecimal listPrice, BigDecimal cost, String prefix, Map<String, Object> fields) {
        String dot = prefix.isEmpty() ? "" : ".";
        if (listPrice == null && cost == null) {
            fields.put(prefix + dot + "listPrice", "A list price or a cost is required.");
            return;
        }
        if (listPrice != null && listPrice.signum() <= 0) {
            fields.put(prefix + dot + "listPrice", "must be greater than 0");
        }
        if (cost != null && cost.signum() < 0) {
            fields.put(prefix + dot + "cost", "must not be negative");
        }
    }

    // ---- detail --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    PriceDetail detail(String item, String storeCode) {
        Catalogue.ProductRef product = catalogue.product(item).orElseThrow(() -> ApiException.notFound("Item", item));
        String code = blankToNull(storeCode);
        Catalogue.StoreRef store = code == null ? null
                : catalogue.store(code).orElseThrow(() -> ApiException.notFound("Store", code));
        UUID storeId = store == null ? null : store.id();
        LocalDate today = clock.today();
        Optional<PriceList.CurrentPrice> current = priceList.current(product.id(), storeId, today);
        PriceDetail.Current currentView = current.map(c -> new PriceDetail.Current(
                PricingMath.round2(c.listPrice()), c.listPriceSource(), PricingMath.round2(c.cost()), c.costSource(),
                c.listPriceSource() != null ? c.listPriceSource() : c.costSource(), c.listPriceSource(),
                c.listPriceFrom() != null ? c.listPriceFrom() : c.costFrom(),
                c.listPrice() != null ? c.listPriceStoreSpecific() : c.costStoreSpecific())).orElse(null);
        PriceDetail.Figure price = ladder.currentPrice(product.id(), storeId, today).map(PricesService::figure).orElse(null);
        PriceDetail.Figure cost = ladder.cost(product.id(), storeId, today).map(PricesService::figure).orElse(null);
        return new PriceDetail(product.itemNumber(), store == null ? null : store.storeCode(), currency(), currentView,
                new PriceDetail.Ladder(price, cost), history(product.id(), storeId));
    }

    private static PriceDetail.Figure figure(Resolved resolved) {
        return new PriceDetail.Figure(PricingMath.round2(resolved.value()), resolved.source(), resolved.asOf());
    }

    /** The last twenty rows written for the pair (or tenant-wide), newest first. */
    private List<PriceDetail.HistoryRow> history(UUID productId, UUID storeIdOrNull) {
        UUID tenant = TenantContext.requireTenantId();
        String scope = storeIdOrNull != null ? "(pp.store_id = ? OR pp.store_id IS NULL)" : "pp.store_id IS NULL";
        Object[] args = storeIdOrNull != null ? new Object[] {tenant, productId, storeIdOrNull}
                : new Object[] {tenant, productId};
        return jdbc.query("""
                SELECT pp.effective_from, s.store_code, pp.list_price, pp.cost, pp.source, pp.set_by, pp.basis::text AS basis,
                       pp.created_at, pp.write_id
                  FROM product_prices pp LEFT JOIN stores s ON s.id = pp.store_id
                 WHERE pp.tenant_id = ? AND pp.product_id = ? AND %s
                 ORDER BY pp.created_at DESC, pp.effective_from DESC
                 LIMIT 20
                """.formatted(scope), (rs, i) -> new PriceDetail.HistoryRow(
                rs.getObject("effective_from", LocalDate.class), rs.getString("store_code"),
                PricingMath.round2(rs.getBigDecimal("list_price")), PricingMath.round2(rs.getBigDecimal("cost")),
                rs.getString("source"), rs.getObject("set_by", UUID.class), parseBasis(rs.getString("basis")),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("write_id", UUID.class)), args);
    }

    private Map<String, Object> parseBasis(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (java.io.IOException ex) {
            return Map.of("raw", raw);
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    /** Seats whose persona opens the {@code sell} module, and the commercial director. */
    void requireSellSeat() {
        String role = TenantContext.current().map(TenantContext.Actor::role).orElse(null);
        if (role == null) {
            throw sellSeatRequired();
        }
        if ("both".equals(role)) {
            return;
        }
        List<String> modules = jdbc.query("""
                SELECT modules FROM role_policy
                 WHERE role = ? AND (tenant_id = ? OR tenant_id IS NULL)
                 ORDER BY tenant_id NULLS LAST LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return List.<String>of();
            }
            Array array = rs.getArray("modules");
            Object raw = array == null ? null : array.getArray();
            return raw instanceof String[] values ? Arrays.asList(values) : List.<String>of();
        }, role, TenantContext.requireTenantId());
        if (modules == null || !modules.contains("sell")) {
            throw sellSeatRequired();
        }
    }

    private static ApiException sellSeatRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, "sell_seat_required",
                "Setting prices needs a sales seat or the commercial director.");
    }

    String currency() {
        UUID tenant = TenantContext.requireTenantId();
        List<String> found = jdbc.queryForList("SELECT trading_currency FROM tenant_settings WHERE tenant_id = ?",
                String.class, tenant);
        if (!found.isEmpty() && found.get(0) != null) {
            return found.get(0);
        }
        List<String> fallback = jdbc.queryForList("SELECT trading_currency FROM tenants WHERE id = ?", String.class,
                tenant);
        return fallback.isEmpty() || fallback.get(0) == null ? "USD" : fallback.get(0);
    }

    private static String key(String category) {
        return category == null ? "" : category.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

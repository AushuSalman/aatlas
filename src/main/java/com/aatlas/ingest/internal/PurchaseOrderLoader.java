package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.PurchaseRow;
import com.aatlas.ingest.internal.csv.ValidationContext;
import com.aatlas.suppliers.SupplierResolver;
import com.aatlas.suppliers.SupplierResolver.SupplierRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes accepted purchase-order lines into {@code purchase_order}, the procurement ledger.
 *
 * <p>One row per line. The file's PO number goes to {@code po_ref} (many lines share it and a
 * file can be re-imported); {@code po_number} is generated per row. Every baseline/target
 * figure is inserted as a placeholder and then restated by one tenant-wide UPDATE (spec A
 * 3.6a), so targets are a property of the data rather than of load order. The supplier's
 * ex-works price and observed lead time per item are upserted into {@code supplier_products}
 * afterwards, never overwriting a quote a products file supplied.
 */
@Component
class PurchaseOrderLoader implements KindLoader<PurchaseRow> {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderLoader.class);

    static final int BATCH_SIZE = 1_000;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final String INSERT_SQL = """
            insert into purchase_order (
                tenant_id, seq, po_number, po_ref, order_date,
                supplier_id, supplier_name, country,
                item_number, product_id, description, category,
                branch_id, branch_name, store_id, region_key, region_label,
                qty, qty_received, ex_works, freight, duty, landed,
                baseline, target, followed, spend, baseline_spend, saved, leaked,
                status, promised_days, actual_days, days_late, on_time, promised_date, received_date,
                source, import_batch_id, source_line, unit_cost_currency, cost_basis)
            values (?, ?, ?, ?, ?,  ?, ?, ?,  ?, ?, ?, ?,  ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?, ?, ?,  ?, ?, ?, ?, ?)
            """;

    /**
     * Spec A 3.6a: for every imported row of the tenant, the target is the best landed cost
     * paid for the item in the 365 days before the order, the baseline the quantity-weighted
     * average over the same window; a first order is its own baseline and target.
     */
    static final String BASELINE_SQL = """
            with w as (
              select id,
                min(landed) over (partition by tenant_id, item_number order by order_date::timestamp
                                  range between interval '365 days' preceding and interval '1 day' preceding) best,
                sum(landed * qty) over (partition by tenant_id, item_number order by order_date::timestamp
                                  range between interval '365 days' preceding and interval '1 day' preceding)
                  / nullif(sum(qty) over (partition by tenant_id, item_number order by order_date::timestamp
                                  range between interval '365 days' preceding and interval '1 day' preceding), 0) base
              from purchase_order where tenant_id = ?)
            update purchase_order po set
                target         = coalesce(w.best, po.landed),
                baseline       = coalesce(w.base, po.landed),
                followed       = po.landed <= coalesce(w.best, po.landed) * 1.005,
                baseline_spend = coalesce(w.base, po.landed) * po.qty,
                saved          = greatest(0, coalesce(w.base, po.landed) - po.landed) * po.qty,
                leaked         = greatest(0, po.landed - coalesce(w.best, po.landed)) * po.qty
            from w where po.id = w.id and po.tenant_id = ? and po.source in ('import', 'sample')
            """;

    /**
     * The supplier's price list as observed from what was actually paid: the last ex-works
     * cost by order date, the average actual lead over received lines. A quote a products
     * file supplied ({@code ex_works_source = 'import'}) is never overwritten. Scoped by the
     * trailing predicate: the pairs one batch touched (commit) or every observed pair (rollback).
     */
    private static final String SUPPLIER_PRODUCTS_SQL = """
            insert into supplier_products (
                tenant_id, supplier_id, product_id, ex_works, lead_time_days, ex_works_source, ex_works_as_of, import_batch_id)
            select po.tenant_id, s.id, po.product_id,
                   (array_agg(po.ex_works order by po.order_date desc, po.source_line desc nulls last, po.seq desc))[1],
                   case when count(po.actual_days) > 0 then round(avg(po.actual_days))::int end,
                   'purchases',
                   max(po.order_date),
                   ?
              from purchase_order po
              join suppliers s on s.tenant_id = po.tenant_id and s.supplier_key = po.supplier_id
             where po.tenant_id = ? and po.product_id is not null and po.source in ('import', 'sample')
               and %s
             group by po.tenant_id, s.id, po.product_id
            on conflict (tenant_id, supplier_id, product_id) do update set
               ex_works        = case when supplier_products.ex_works_source = 'import'
                                      then supplier_products.ex_works else excluded.ex_works end,
               ex_works_as_of  = case when supplier_products.ex_works_source = 'import'
                                      then supplier_products.ex_works_as_of else excluded.ex_works_as_of end,
               ex_works_source = case when supplier_products.ex_works_source = 'import'
                                      then supplier_products.ex_works_source else excluded.ex_works_source end,
               lead_time_days  = coalesce(excluded.lead_time_days, supplier_products.lead_time_days),
               import_batch_id = excluded.import_batch_id,
               updated_at      = now()
            """;

    static final String PAIRS_OF_BATCH = """
            (po.supplier_id, po.product_id) in (
                select distinct b.supplier_id, b.product_id from purchase_order b
                 where b.tenant_id = ? and b.import_batch_id = ?)""";

    static final String PAIRS_OBSERVED = """
            exists (select 1 from supplier_products sp
                     where sp.tenant_id = po.tenant_id and sp.supplier_id = s.id
                       and sp.product_id = po.product_id and sp.ex_works_source = 'purchases')""";

    private final JdbcTemplate jdbc;
    private final SupplierResolver suppliers;

    PurchaseOrderLoader(JdbcTemplate jdbc, SupplierResolver suppliers) {
        this.jdbc = jdbc;
        this.suppliers = suppliers;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.PURCHASES;
    }

    @Override
    public Load<PurchaseRow> begin(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
        return new PurchaseLoad(tenantId, batchId, source, ctx);
    }

    /** Restates baseline/target/saved/leaked for every imported row of the tenant. */
    void recomputeBaselines(UUID tenantId) {
        jdbc.update(BASELINE_SQL, tenantId, tenantId);
    }

    /** Re-derives the observed price list for the pairs one batch touched. */
    int upsertSupplierProductsForBatch(UUID tenantId, UUID batchId) {
        return jdbc.update(SUPPLIER_PRODUCTS_SQL.formatted(PAIRS_OF_BATCH), batchId, tenantId, tenantId, batchId);
    }

    /** Re-derives every pair whose figures came from purchases; pairs with no rows left are cleared. */
    void refreshObservedSupplierProducts(UUID tenantId) {
        jdbc.update("""
                update supplier_products sp
                   set ex_works = null, ex_works_as_of = null, lead_time_days = null,
                       ex_works_source = null, import_batch_id = null, updated_at = now()
                 where sp.tenant_id = ? and sp.ex_works_source = 'purchases'
                   and not exists (
                       select 1 from purchase_order po
                         join suppliers s on s.tenant_id = po.tenant_id and s.supplier_key = po.supplier_id
                        where po.tenant_id = sp.tenant_id and s.id = sp.supplier_id
                          and po.product_id = sp.product_id and po.source in ('import', 'sample'))
                """, tenantId);
        jdbc.update(SUPPLIER_PRODUCTS_SQL.formatted(PAIRS_OBSERVED), ps -> {
            ps.setObject(1, null, java.sql.Types.OTHER);
            ps.setObject(2, tenantId);
        });
    }

    /** One load in progress. */
    final class PurchaseLoad implements Load<PurchaseRow> {

        private final UUID tenantId;
        private final UUID batchId;
        private final String rowSource;
        private final LocalDate today;
        private final String currency;
        private final CatalogueIndex index;
        private final Map<String, String> regionLabels;
        private final Map<String, BigDecimal[]> origins;
        private final Map<String, SupplierRef> suppliersByName = new HashMap<>();
        private final Set<String> supplierKeys = new LinkedHashSet<>();
        private final List<String> suppliersCreatedNames = new ArrayList<>();
        private final String poPrefix;

        private final List<Object[]> pending = new ArrayList<>(BATCH_SIZE);
        private int seq;
        private int loaded;
        private int receivedRows;
        private int suppliersCreated;

        private PurchaseLoad(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
            this.tenantId = tenantId;
            this.batchId = batchId;
            this.rowSource = CatalogueIndex.rowSource(source);
            this.today = ctx.today();
            this.currency = ctx.tenantCurrency();
            this.index = new CatalogueIndex(jdbc, tenantId, batchId, source);
            this.regionLabels = index.regionLabels();
            this.origins = loadOrigins();
            this.poPrefix = "IMP-" + batchId.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            Integer maxSeq = jdbc.queryForObject(
                    "select coalesce(max(seq), 0) from purchase_order where tenant_id = ?", Integer.class, tenantId);
            this.seq = maxSeq == null ? 0 : maxSeq;
        }

        @Override
        public void add(PurchaseRow row) {
            CatalogueIndex.ProductRef product = index.product(row.item(), row.description(), false);
            CatalogueIndex.StoreRef store = index.store(row.shipTo());
            SupplierRef supplier = resolveSupplier(row.supplier(), row.supplierCountry());
            supplierKeys.add(supplier.supplierKey());

            Costs costs = costs(row, supplier.country());
            BigDecimal qty = BigDecimal.valueOf(row.qty());
            BigDecimal spend = money(costs.landed.multiply(qty));

            Integer promisedDays = row.promisedDate() == null ? null
                    : (int) ChronoUnit.DAYS.between(row.orderDate(), row.promisedDate());
            Integer actualDays = row.receivedDate() == null ? null
                    : (int) ChronoUnit.DAYS.between(row.orderDate(), row.receivedDate());
            Integer daysLate = null;
            Boolean onTime = null;
            if (row.receivedDate() != null && row.promisedDate() != null) {
                daysLate = (int) Math.max(0, ChronoUnit.DAYS.between(row.promisedDate(), row.receivedDate()));
                onTime = !row.receivedDate().isAfter(row.promisedDate())
                        && (row.qtyReceived() == null || row.qtyReceived() >= row.qty());
            }
            String status = status(row, promisedDays);
            if (row.receivedDate() != null) {
                receivedRows++;
            }

            String description = row.description() == null || row.description().isBlank()
                    ? product.description() : row.description().strip();
            String regionLabel = regionLabels.getOrDefault(store.regionKey(), store.regionKey());

            seq++;
            pending.add(new Object[] {
                tenantId, seq, poPrefix + "-" + String.format("%06d", row.line()),
                SalesTransactionLoader.blankToNull(row.poNumber()), row.orderDate(),
                supplier.supplierKey(), supplier.name(), supplier.country(),
                row.item().strip(), product.id(), description, product.category(),
                store.code(), store.name(), store.id(), store.regionKey(), regionLabel,
                row.qty(), row.qtyReceived(), money(row.unitCost()), costs.freight, costs.duty, costs.landed,
                costs.landed, costs.landed, true, spend, spend, BigDecimal.ZERO, BigDecimal.ZERO,
                status, promisedDays, actualDays, daysLate, onTime, row.promisedDate(), row.receivedDate(),
                rowSource, batchId, row.line(), currency, costs.basis
            });
            if (pending.size() >= BATCH_SIZE) {
                flush();
            }
        }

        @Override
        public LoadResult finish() {
            flush();
            recomputeBaselines(tenantId);
            int links = upsertSupplierProductsForBatch(tenantId, batchId);
            log.info("Purchases import {}: {} lines, {} suppliers ({} created), {} products created, {} links",
                    batchId, loaded, supplierKeys.size(), suppliersCreated, index.productsCreated(), links);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("suppliersCreated", suppliersCreated);
            summary.put("suppliersCreatedNames", List.copyOf(
                    suppliersCreatedNames.subList(0, Math.min(suppliersCreatedNames.size(), CatalogueIndex.MAX_REPORTED))));
            summary.put("supplierLinksWritten", links);
            summary.put("receivedRows", receivedRows);
            return new LoadResult(loaded, index.productsCreated(), index.branchesCreated(),
                    index.branchesNeedingRegion(), supplierKeys.size(), summary);
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            jdbc.batchUpdate(INSERT_SQL, pending, pending.size(), SalesTransactionLoader::bind);
            loaded += pending.size();
            pending.clear();
        }

        /** {@code find} first (key, vendor code or name), else create with what the file said. */
        private SupplierRef resolveSupplier(String name, String countryRaw) {
            String key = CatalogueIndex.key(name);
            SupplierRef cached = suppliersByName.get(key);
            if (cached != null) {
                return cached;
            }
            SupplierRef ref = suppliers.find(tenantId, name).orElseGet(() -> {
                SupplierRef created = suppliers.create(tenantId, name, countryRaw, "po_import", batchId);
                suppliersCreated++;
                suppliersCreatedNames.add(created.name());
                return created;
            });
            suppliersByName.put(key, ref);
            return ref;
        }

        /**
         * Freight, duty and landed cost, and where they came from: the file's landed cost,
         * the file's components, or the reference lane for the supplier's country.
         */
        private Costs costs(PurchaseRow row, String supplierCountry) {
            BigDecimal unit = money(row.unitCost());
            if (row.landedCost() != null) {
                BigDecimal duty = money(row.duty() == null ? BigDecimal.ZERO : row.duty());
                BigDecimal freight = row.freight() != null ? money(row.freight())
                        : money(row.landedCost().subtract(unit).subtract(duty)).max(BigDecimal.ZERO);
                return new Costs(freight, duty, money(row.landedCost()), "file");
            }
            if (row.freight() != null || row.duty() != null) {
                BigDecimal freight = money(row.freight() == null ? BigDecimal.ZERO : row.freight());
                BigDecimal duty = money(row.duty() == null ? BigDecimal.ZERO : row.duty());
                return new Costs(freight, duty, money(unit.add(freight).add(duty)), "components");
            }
            BigDecimal[] lane = supplierCountry == null ? null : origins.get(supplierCountry);
            if (lane != null) {
                BigDecimal freight = money(unit.multiply(lane[0]).divide(HUNDRED, 6, RoundingMode.HALF_UP));
                BigDecimal duty = money(unit.multiply(lane[1]).divide(HUNDRED, 6, RoundingMode.HALF_UP));
                return new Costs(freight, duty, money(unit.add(freight).add(duty)), "lane-estimate");
            }
            return new Costs(BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), unit, "components");
        }

        /**
         * Mirrors the ledger's own rule: received when a received date exists; otherwise in
         * transit once roughly a third of the promised lead has elapsed, else open.
         */
        private String status(PurchaseRow row, Integer promisedDays) {
            if (row.receivedDate() != null) {
                return "received";
            }
            if (promisedDays != null && today != null) {
                long elapsedThreshold = (long) Math.floor(promisedDays * 0.35);
                if (!row.orderDate().plusDays(elapsedThreshold).isAfter(today)) {
                    return "in-transit";
                }
            }
            return "open";
        }

        private Map<String, BigDecimal[]> loadOrigins() {
            Map<String, BigDecimal[]> map = new HashMap<>();
            jdbc.query("select country, inbound_pct, duty_pct from logistics_origins", rs -> {
                map.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
            });
            return map;
        }
    }

    private record Costs(BigDecimal freight, BigDecimal duty, BigDecimal landed, String basis) {
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}

package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ImportReport.ParsedRow;
import com.aatlas.ingest.internal.csv.ValidationContext;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * Writes accepted sales rows into {@code sales_transactions}.
 *
 * <p>Products are created for item numbers the catalogue has not seen, customers for names
 * it has not seen (segment {@code unassigned}), branches for codes it has not seen (region
 * {@code unassigned}); a blank branch is the tenant's main branch. See {@link CatalogueIndex}
 * for why none of those rejects a row.
 */
@Component
class SalesTransactionLoader implements KindLoader<ParsedRow> {

    private static final Logger log = LoggerFactory.getLogger(SalesTransactionLoader.class);

    /** Rows per batch. Large enough to amortise the round trip, small enough to stay bounded. */
    static final int BATCH_SIZE = 1_000;

    private static final String INSERT_SQL = """
            insert into sales_transactions (
                tenant_id, txn_date, product_id, store_id, customer_id,
                item_number, branch_code, customer_code, description,
                qty, unit_price, unit_cost, source, import_batch_id, source_line,
                invoice_no, currency, uom)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Records that this branch sells this item, widening the first/last sale window as more
     * rows arrive. A row here is what makes an (item, branch) pair priceable at all. The
     * batch id is written only on insert: the link belongs to the import that created it.
     */
    private static final String LINK_SQL = """
            insert into product_stores (tenant_id, product_id, store_id, sells, first_sale_at, last_sale_at, import_batch_id)
            values (?, ?, ?, true, ?, ?, ?)
            on conflict (tenant_id, product_id, store_id) do update
               set sells        = true,
                   first_sale_at = least(product_stores.first_sale_at, excluded.first_sale_at),
                   last_sale_at  = greatest(product_stores.last_sale_at, excluded.last_sale_at),
                   updated_at    = now()
            """;

    private final JdbcTemplate jdbc;

    SalesTransactionLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.SALES;
    }

    @Override
    public Load<ParsedRow> begin(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
        return new SalesLoad(tenantId, batchId, source);
    }

    /** A load in progress. One instance per import; not thread-safe and not meant to be. */
    final class SalesLoad implements Load<ParsedRow> {

        private final UUID tenantId;
        private final UUID batchId;
        private final String rowSource;
        private final CatalogueIndex index;
        private final Set<LocalDate> ensuredMonths = new HashSet<>();
        private final Set<ProductStoreLink> links = new LinkedHashSet<>();

        private final List<Object[]> pending = new ArrayList<>(BATCH_SIZE);
        private int loaded;

        private SalesLoad(UUID tenantId, UUID batchId, String source) {
            this.tenantId = tenantId;
            this.batchId = batchId;
            this.rowSource = CatalogueIndex.rowSource(source);
            this.index = new CatalogueIndex(jdbc, tenantId, batchId, source);
        }

        @Override
        public void add(ParsedRow row) {
            UUID productId = index.product(row.item(), row.description(), true).id();
            CatalogueIndex.StoreRef store = index.store(row.branch());
            UUID customerId = index.customer(row.customer());

            ensureMonth(row.date());

            pending.add(new Object[] {
                tenantId, row.date(), productId, store.id(), customerId,
                row.item().strip(), blankToNull(row.branch()), blankToNull(row.customer()),
                blankToNull(row.description()),
                row.qty(), row.price(), row.cost(), rowSource, batchId, row.line(),
                blankToNull(row.invoiceNo()), row.currency(), blankToNull(row.uom())
            });

            links.add(new ProductStoreLink(productId, store.id(), row.date()));
            if (pending.size() >= BATCH_SIZE) {
                flush();
            }
        }

        @Override
        public LoadResult finish() {
            flush();
            writeLinks();
            // A product a price list or a purchase order created is catalogued-but-unsold until
            // a sales line proves otherwise.
            jdbc.update("""
                    update products p set has_sales = true, updated_at = now()
                     where p.tenant_id = ? and p.has_sales = false
                       and exists (select 1 from sales_transactions st
                                    where st.tenant_id = p.tenant_id and st.product_id = p.id
                                      and st.import_batch_id = ?)
                    """, tenantId, batchId);
            log.info("Sales import {}: {} rows, {} products, {} branches, {} customers created",
                    batchId, loaded, index.productsCreated(), index.branchesCreated(), index.customersCreated());
            return new LoadResult(
                    loaded,
                    index.productsCreated(),
                    index.branchesCreated(),
                    index.branchesNeedingRegion(),
                    0,
                    Map.of("customersCreated", index.customersCreated()));
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            jdbc.batchUpdate(INSERT_SQL, pending, pending.size(),
                    (PreparedStatement ps, Object[] row) -> bind(ps, row));
            loaded += pending.size();
            pending.clear();
        }

        private void writeLinks() {
            if (links.isEmpty()) {
                return;
            }
            // One link per pair with the widest window, so the upsert never has to
            // touch the same row twice in one statement.
            Map<String, ProductStoreLink> merged = new java.util.LinkedHashMap<>();
            for (ProductStoreLink link : links) {
                merged.merge(link.productId() + "|" + link.storeId(), link, ProductStoreLink::widen);
            }
            List<ProductStoreLink> batch = List.copyOf(merged.values());
            jdbc.batchUpdate(LINK_SQL, batch, batch.size(), (ps, link) -> {
                ps.setObject(1, tenantId);
                ps.setObject(2, link.productId());
                ps.setObject(3, link.storeId());
                ps.setObject(4, java.sql.Date.valueOf(link.first()));
                ps.setObject(5, java.sql.Date.valueOf(link.last()));
                ps.setObject(6, batchId);
            });
        }

        /**
         * Creates the monthly partition the first time a date in that month is seen.
         *
         * <p>A query rather than an update: the function returns void, but PostgreSQL still
         * answers {@code SELECT} with a one-row result set, and {@code update()} treats any
         * result at all as an error.
         */
        private void ensureMonth(LocalDate date) {
            LocalDate month = date.withDayOfMonth(1);
            if (ensuredMonths.add(month)) {
                jdbc.query(
                        "select app.ensure_month_partition('sales_transactions', ?)",
                        (ResultSetExtractor<Void>) rs -> null,
                        date);
            }
        }
    }

    private record ProductStoreLink(UUID productId, UUID storeId, LocalDate first, LocalDate last) {

        ProductStoreLink(UUID productId, UUID storeId, LocalDate date) {
            this(productId, storeId, date, date);
        }

        ProductStoreLink widen(ProductStoreLink other) {
            return new ProductStoreLink(productId, storeId,
                    other.first.isBefore(first) ? other.first : first,
                    other.last.isAfter(last) ? other.last : last);
        }
    }

    static void bind(PreparedStatement ps, Object[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            if (value == null) {
                // Untyped nulls confuse the driver on a uuid column; OTHER is the safe say-nothing.
                ps.setObject(i + 1, null, Types.OTHER);
            } else if (value instanceof LocalDate date) {
                ps.setObject(i + 1, java.sql.Date.valueOf(date));
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

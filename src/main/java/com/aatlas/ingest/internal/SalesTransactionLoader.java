package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.ImportReport.ParsedRow;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * Writes accepted rows into {@code sales_transactions}.
 *
 * <p>JDBC rather than JPA, and that is the whole design. A 24-month export is hundreds of
 * thousands of rows; persisting each through an entity manager means a managed object, a
 * dirty check and a flush per row, and a first-level cache that grows until the heap gives
 * out. Batched prepared statements write the same rows in a fraction of the time and a
 * constant amount of memory. {@code sales_transactions} is deliberately not mapped as an
 * entity anywhere, so nobody can undo this by accident.
 *
 * <p>Resolution follows a rule worth stating: <b>an unknown branch or customer must not
 * reject a row.</b> The history is true whether or not this platform has been told about
 * the branch yet, and refusing it would make importing a real ERP export impossible until
 * every branch had been set up by hand first. Unknown codes are kept on the row and
 * reported back so the user can see what went unmatched. Products are the exception: they
 * are created, because an item with no product row can never be priced and the file gives
 * us everything a product needs.
 */
@Component
class SalesTransactionLoader {

    private static final Logger log = LoggerFactory.getLogger(SalesTransactionLoader.class);

    /** Rows per batch. Large enough to amortise the round trip, small enough to stay bounded. */
    private static final int BATCH_SIZE = 1_000;

    /** How many unmatched codes to report back. A list nobody can read is not a report. */
    private static final int MAX_REPORTED_UNRESOLVED = 50;

    private static final String INSERT_SQL = """
            insert into sales_transactions (
                tenant_id, txn_date, product_id, store_id, customer_id,
                item_number, branch_code, customer_code, description,
                qty, unit_price, unit_cost, source, import_batch_id, source_line)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'import', ?, ?)
            """;

    /**
     * Records that this branch sells this item, widening the first/last sale window as more
     * rows arrive. A row here is what makes an (item, branch) pair priceable at all.
     */
    private static final String LINK_SQL = """
            insert into product_stores (tenant_id, product_id, store_id, sells, first_sale_at, last_sale_at)
            values (?, ?, ?, true, ?, ?)
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

    /** What a load actually did, as opposed to what validation predicted it would do. */
    record LoadResult(
            int loadedRows,
            int productsCreated,
            int branchesCreated,
            List<String> branchesNeedingRegion,
            List<String> unresolvedCustomers) {
    }

    /**
     * A load in progress.
     *
     * <p>Holds the resolution caches and the pending batch. One instance per import; not
     * thread-safe and not meant to be.
     */
    final class Load {

        private final UUID tenantId;
        private final UUID batchId;

        private final String country;
        private final Map<String, UUID> productsByItem;
        private final Map<String, UUID> storesByCode;
        private final Map<String, UUID> customersByCode;
        private final Set<LocalDate> ensuredMonths = new HashSet<>();
        private final Set<String> branchesNeedingRegion = new LinkedHashSet<>();
        private final Set<String> unresolvedCustomers = new LinkedHashSet<>();
        private final Set<ProductStoreLink> links = new LinkedHashSet<>();

        private final List<Object[]> pending = new ArrayList<>(BATCH_SIZE);
        private int loaded;
        private int productsCreated;
        private int branchesCreated;

        private Load(UUID tenantId, UUID batchId) {
            this.tenantId = tenantId;
            this.batchId = batchId;
            this.country = countryOf(tenantId);
            this.productsByItem = existingProducts(tenantId);
            this.storesByCode = existingStores(tenantId);
            this.customersByCode = existingCustomers(tenantId);
        }

        void add(ParsedRow row) {
            UUID productId = resolveProduct(row);
            UUID storeId = resolveStore(row.branch());
            UUID customerId = resolve(customersByCode, row.customer(), unresolvedCustomers);

            ensureMonth(row.date());

            pending.add(new Object[] {
                tenantId, row.date(), productId, storeId, customerId,
                row.item(), blankToNull(row.branch()), blankToNull(row.customer()),
                blankToNull(row.description()),
                row.qty(), row.price(), row.cost(), batchId, row.line()
            });

            if (storeId != null) {
                links.add(new ProductStoreLink(productId, storeId, row.date()));
            }
            if (pending.size() >= BATCH_SIZE) {
                flush();
            }
        }

        LoadResult finish() {
            flush();
            writeLinks();
            return new LoadResult(
                    loaded,
                    productsCreated,
                    branchesCreated,
                    capped(branchesNeedingRegion),
                    capped(unresolvedCustomers));
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
            List<ProductStoreLink> batch = List.copyOf(links);
            jdbc.batchUpdate(LINK_SQL, batch, batch.size(), (ps, link) -> {
                ps.setObject(1, tenantId);
                ps.setObject(2, link.productId());
                ps.setObject(3, link.storeId());
                ps.setObject(4, link.date());
                ps.setObject(5, link.date());
            });
        }

        /**
         * Finds the branch, or creates it with no region.
         *
         * <p>The file gives a code and nothing else. The four market regions drive the map,
         * the regional rollups and part of the pricing signal, and {@code 200110} says
         * nothing about which one it belongs to - so the branch is created as
         * {@code unassigned} rather than placed on a guess. It counts as a real branch
         * everywhere a branch is needed, and is excluded from anything regional until a
         * person places it.
         */
        private UUID resolveStore(String branch) {
            if (branch == null || branch.isBlank()) {
                return null;
            }
            String code = branch.strip();
            UUID existing = storesByCode.get(key(code));
            if (existing != null) {
                return existing;
            }
            UUID created = createStore(tenantId, code, country);
            storesByCode.put(key(code), created);
            branchesNeedingRegion.add(code);
            branchesCreated++;
            return created;
        }

        private UUID resolveProduct(ParsedRow row) {
            String key = key(row.item());
            UUID existing = productsByItem.get(key);
            if (existing != null) {
                return existing;
            }
            UUID created = createProduct(tenantId, row.item(), row.description());
            productsByItem.put(key, created);
            productsCreated++;
            return created;
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

    Load begin(UUID tenantId, UUID batchId) {
        return new Load(tenantId, batchId);
    }

    private record ProductStoreLink(UUID productId, UUID storeId, LocalDate date) {
    }

    private static void bind(PreparedStatement ps, Object[] row) throws SQLException {
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

    /**
     * Creates a product from what the file knows about it.
     *
     * <p>Category and subcategory are left as {@code uncategorised} rather than guessed.
     * They drive filters and the insights rollups, and a wrong category is worse than an
     * obviously absent one: nobody audits a plausible answer.
     */
    private UUID createProduct(UUID tenantId, String itemNumber, String description) {
        String safeDescription = description == null || description.isBlank() ? itemNumber : description.strip();
        String shortName = safeDescription.length() <= 40 ? safeDescription : safeDescription.substring(0, 40).strip();
        return jdbc.queryForObject("""
                insert into products (
                    tenant_id, item_number, description, short_name,
                    category, subcategory, commodity, unit, has_sales)
                values (?, ?, ?, ?, 'uncategorised', 'uncategorised', 'none', 'each', true)
                on conflict (tenant_id, item_number) do update set has_sales = true
                returning id
                """, UUID.class, tenantId, itemNumber, safeDescription, shortName);
    }

    /**
     * Creates a branch from its code alone.
     *
     * <p>{@code legal_name} is the code until someone renames it - the column is
     * {@code NOT NULL} and the file has nothing better to offer. The country comes from the
     * tenant, which is the one piece of placement that is actually known.
     */
    private UUID createStore(UUID tenantId, String storeCode, String country) {
        return jdbc.queryForObject("""
                insert into stores (tenant_id, store_code, legal_name, country, region_key, source)
                values (?, ?, ?, ?, 'unassigned', 'import')
                on conflict (tenant_id, store_code) do update set store_code = excluded.store_code
                returning id
                """, UUID.class, tenantId, storeCode, "Branch " + storeCode, country);
    }

    /** The tenant's country, which every branch it owns inherits. */
    private String countryOf(UUID tenantId) {
        return jdbc.queryForObject("select country from tenants where id = ?", String.class, tenantId);
    }

    private Map<String, UUID> existingProducts(UUID tenantId) {
        return indexBy("select item_number, id from products where tenant_id = ?", tenantId);
    }

    private Map<String, UUID> existingStores(UUID tenantId) {
        return indexBy("select store_code, id from stores where tenant_id = ?", tenantId);
    }

    /**
     * Customers by code and by name.
     *
     * <p>Exports are inconsistent about which one they put in the "Bill To" column, and
     * matching on either is the difference between a customer resolving and a whole column
     * being reported as unknown. A name that collides with another customer's code is
     * vanishingly unlikely and would resolve to one of them; the row still loads either way.
     */
    private Map<String, UUID> existingCustomers(UUID tenantId) {
        Map<String, UUID> byKey = new HashMap<>();
        jdbc.query("select code, name, id from customers where tenant_id = ?", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            byKey.putIfAbsent(key(rs.getString("code")), id);
            byKey.putIfAbsent(key(rs.getString("name")), id);
        }, tenantId);
        return byKey;
    }

    private Map<String, UUID> indexBy(String sql, UUID tenantId) {
        Map<String, UUID> byKey = new HashMap<>();
        jdbc.query(sql, rs -> {
            byKey.putIfAbsent(key(rs.getString(1)), rs.getObject(2, UUID.class));
        }, tenantId);
        return byKey;
    }

    private static UUID resolve(Map<String, UUID> index, String raw, Set<String> unresolved) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        UUID id = index.get(key(raw));
        if (id == null) {
            unresolved.add(raw.strip());
        }
        return id;
    }

    /** Case and padding are not meaningful in an ERP code; {@code WH-01} and {@code wh-01} are one branch. */
    private static String key(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> capped(Set<String> values) {
        if (values.size() > MAX_REPORTED_UNRESOLVED) {
            log.info("{} unmatched codes in this import; reporting the first {}",
                    values.size(), MAX_REPORTED_UNRESOLVED);
        }
        return values.stream().limit(MAX_REPORTED_UNRESOLVED).toList();
    }
}

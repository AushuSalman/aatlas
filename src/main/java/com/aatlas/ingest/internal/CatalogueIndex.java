package com.aatlas.ingest.internal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tenant's products, branches and customers as one import sees them: pre-indexed once
 * at the start of a load, created on first sight after that.
 *
 * <p>Resolution follows a rule worth stating: <b>an unknown code must not reject a row.</b>
 * The history is true whether or not this platform has been told about the branch or the
 * customer yet, and refusing it would make importing a real ERP export impossible until
 * every one had been set up by hand first. So an unknown branch becomes a branch with no
 * region, an unknown customer becomes a customer with no segment, an unknown item becomes
 * an uncategorised product - each tagged with the batch that created it, so a rollback can
 * find them, and each reported back so a person can finish them.
 *
 * <p>A blank branch resolves to the tenant's {@code MAIN} store ("Main branch"), created
 * once. The consequence matters: no committed import leaves {@code stores} empty, so the
 * catalogue gate never refuses a workspace that has history.
 */
final class CatalogueIndex {

    private static final Logger log = LoggerFactory.getLogger(CatalogueIndex.class);

    /** How many created codes to report back. A list nobody can read is not a report. */
    static final int MAX_REPORTED = 50;

    static final String MAIN_STORE_CODE = "MAIN";

    record ProductRef(UUID id, String category, String description) {
    }

    record StoreRef(UUID id, String code, String name, String regionKey) {
    }

    private final JdbcTemplate jdbc;
    private final UUID tenantId;
    private final UUID batchId;
    /** {@code import} or {@code sample}: what the created rows are tagged with. */
    private final String rowSource;
    private final String country;

    private final Map<String, ProductRef> productsByItem = new HashMap<>();
    private final Map<String, StoreRef> storesByCode = new HashMap<>();
    private final Map<String, UUID> customersByKey = new HashMap<>();
    private final Set<String> branchesNeedingRegion = new LinkedHashSet<>();

    private int productsCreated;
    private int branchesCreated;
    private int customersCreated;

    CatalogueIndex(JdbcTemplate jdbc, UUID tenantId, UUID batchId, String batchSource) {
        this.jdbc = jdbc;
        this.tenantId = tenantId;
        this.batchId = batchId;
        this.rowSource = rowSource(batchSource);
        this.country = jdbc.queryForObject("select country from tenants where id = ?", String.class, tenantId);

        jdbc.query("select item_number, id, category, description from products where tenant_id = ?", rs -> {
            productsByItem.putIfAbsent(key(rs.getString(1)), new ProductRef(
                    rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)));
        }, tenantId);
        jdbc.query("select store_code, id, legal_name, region_key from stores where tenant_id = ?", rs -> {
            storesByCode.putIfAbsent(key(rs.getString(1)), new StoreRef(
                    rs.getObject(2, UUID.class), rs.getString(1), rs.getString(3), rs.getString(4)));
        }, tenantId);
        // Customers by code and by name: exports are inconsistent about which one they put
        // in "Bill To", and matching on either is the difference between an account
        // resolving and a whole column being created twice.
        jdbc.query("select code, name, id from customers where tenant_id = ?", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            customersByKey.putIfAbsent(key(rs.getString("code")), id);
            customersByKey.putIfAbsent(key(rs.getString("name")), id);
        }, tenantId);
    }

    /** The value the {@code source} columns take for rows this batch creates. */
    static String rowSource(String batchSource) {
        return "sample".equals(batchSource) ? "sample" : "import";
    }

    String country() {
        return country;
    }

    /** The product, or null when the catalogue has never seen the item. */
    ProductRef find(String item) {
        return productsByItem.get(key(item));
    }

    /**
     * The product, created from what the file knows about it when unknown.
     *
     * <p>Category and subcategory are left as {@code uncategorised} rather than guessed. They
     * drive filters and the insights rollups, and a wrong category is worse than an obviously
     * absent one: nobody audits a plausible answer.
     *
     * @param hasSales whether the row proves the item sells (a sales line does; a purchase
     *     order or a price does not)
     */
    ProductRef product(String item, String description, boolean hasSales) {
        String k = key(item);
        ProductRef existing = productsByItem.get(k);
        if (existing != null) {
            return existing;
        }
        String safeDescription = description == null || description.isBlank() ? item.strip() : description.strip();
        UUID id = jdbc.queryForObject("""
                insert into products (
                    tenant_id, item_number, description, short_name,
                    category, subcategory, commodity, unit, has_sales, source, import_batch_id)
                values (?, ?, ?, ?, 'uncategorised', 'uncategorised', 'none', 'each', ?, ?, ?)
                on conflict (tenant_id, item_number) do update
                   set has_sales = products.has_sales or excluded.has_sales
                returning id
                """, UUID.class, tenantId, item.strip(), safeDescription, shortName(safeDescription),
                hasSales, rowSource, batchId);
        ProductRef created = new ProductRef(id, "uncategorised", safeDescription);
        productsByItem.put(k, created);
        productsCreated++;
        return created;
    }

    /** Registers a product another loader created through its own SQL. */
    void remember(String item, ProductRef ref, boolean created) {
        productsByItem.put(key(item), ref);
        if (created) {
            productsCreated++;
        }
    }

    /**
     * The branch for a code, created with no region when unknown; the tenant's main branch
     * when the code is blank.
     *
     * <p>The file gives a code and nothing else. The four market regions drive the map, the
     * regional rollups and part of the pricing signal, and {@code 200110} says nothing about
     * which one it belongs to - so the branch is created as {@code unassigned} rather than
     * placed on a guess. It counts as a real branch everywhere a branch is needed, and is
     * excluded from anything regional until a person places it.
     */
    StoreRef store(String code) {
        if (code == null || code.isBlank()) {
            return main();
        }
        String clean = code.strip();
        StoreRef existing = storesByCode.get(key(clean));
        if (existing != null) {
            return existing;
        }
        StoreRef created = createStore(clean, "Branch " + clean);
        branchesNeedingRegion.add(clean);
        branchesCreated++;
        return created;
    }

    /** The tenant's {@code MAIN} store, created once per tenant. */
    StoreRef main() {
        StoreRef existing = storesByCode.get(key(MAIN_STORE_CODE));
        if (existing != null) {
            return existing;
        }
        StoreRef created = createStore(MAIN_STORE_CODE, "Main branch");
        branchesNeedingRegion.add(MAIN_STORE_CODE);
        branchesCreated++;
        return created;
    }

    /** Whether the code names a branch the tenant has (or this load created). */
    StoreRef lookupStore(String code) {
        return code == null ? null : storesByCode.get(key(code));
    }

    /** Whether the tenant has any branch at all, counting the ones this load created. */
    boolean hasStores() {
        return !storesByCode.isEmpty();
    }

    private StoreRef createStore(String storeCode, String legalName) {
        StoreRef ref = jdbc.queryForObject("""
                insert into stores (tenant_id, store_code, legal_name, country, region_key, source, import_batch_id)
                values (?, ?, ?, ?, 'unassigned', ?, ?)
                on conflict (tenant_id, store_code) do update set store_code = excluded.store_code
                returning id, store_code, legal_name, region_key
                """, (rs, i) -> new StoreRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4)),
                tenantId, storeCode, legalName, country, rowSource, batchId);
        storesByCode.put(key(storeCode), ref);
        return ref;
    }

    /**
     * The customer for a "Bill To" value, created with no segment when unknown.
     *
     * <p>A customer the file named and nobody has classified yet is a real state, like an
     * unassigned branch: shown as its own bucket, never guessed. Tier C, repeat profile, a
     * week's SLA and no agreed discount are the neutral defaults a person then edits.
     */
    UUID customer(String billTo) {
        if (billTo == null || billTo.isBlank()) {
            return null;
        }
        String name = billTo.strip();
        UUID existing = customersByKey.get(key(name));
        if (existing != null) {
            return existing;
        }
        String code = name.length() <= 40 ? name : name.substring(0, 40).strip();
        UUID id = jdbc.queryForObject("""
                insert into customers (
                    tenant_id, code, name, segment, tier, agreed_discount_pct, typical_qty, profile, sla_days,
                    source, import_batch_id)
                values (?, ?, ?, 'unassigned', 'C', 0, 1, 'repeat', 7, ?, ?)
                on conflict (tenant_id, code) do update set code = excluded.code
                returning id
                """, UUID.class, tenantId, code, name, rowSource, batchId);
        customersByKey.put(key(name), id);
        customersByKey.putIfAbsent(key(code), id);
        customersCreated++;
        return id;
    }

    int productsCreated() {
        return productsCreated;
    }

    int branchesCreated() {
        return branchesCreated;
    }

    int customersCreated() {
        return customersCreated;
    }

    List<String> branchesNeedingRegion() {
        return capped(branchesNeedingRegion);
    }

    /** Region labels for the tenant's country, {@code unassigned} included. */
    Map<String, String> regionLabels() {
        Map<String, String> labels = new HashMap<>();
        jdbc.query("select region_key, label from regions where country_code = ?",
                rs -> {
                    labels.put(rs.getString(1), rs.getString(2));
                }, country);
        labels.put("unassigned", "Needs a region");
        return labels;
    }

    /** Subdivision code (lower-cased) to region key, for the tenant's country. */
    Map<String, String> subdivisions() {
        Map<String, String> map = new HashMap<>();
        jdbc.query("select code, region_key from subdivisions where country_code = ?",
                rs -> {
                    map.put(key(rs.getString(1)), rs.getString(2));
                }, country);
        return map;
    }

    void forEachStore(Consumer<StoreRef> consumer) {
        storesByCode.values().forEach(consumer);
    }

    static String shortName(String description) {
        return description.length() <= 40 ? description : description.substring(0, 40).strip();
    }

    /** Case and padding are not meaningful in an ERP code; {@code WH-01} and {@code wh-01} are one branch. */
    static String key(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    static List<String> capped(Set<String> values) {
        if (values.size() > MAX_REPORTED) {
            log.info("{} codes to report; reporting the first {}", values.size(), MAX_REPORTED);
        }
        return values.stream().limit(MAX_REPORTED).toList();
    }
}

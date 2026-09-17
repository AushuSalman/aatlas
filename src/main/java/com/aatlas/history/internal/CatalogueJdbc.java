package com.aatlas.history.internal;

import com.aatlas.history.Catalogue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The catalogue as the read layer sees it: products, branches, customers and regions, by JDBC. */
@Repository
class CatalogueJdbc implements Catalogue {

    private static final String PRODUCT_COLUMNS = """
            SELECT p.id, p.item_number, p.description, p.short_name, p.category, p.subcategory, p.commodity, p.unit,
                   p.has_sales, p.default_store_code, p.source
              FROM products p
            """;

    private static final String STORE_COLUMNS = """
            SELECT s.id, s.store_code, s.legal_name, s.country, s.subdivision_code, s.msa_name, s.rpp, s.txns, s.segment,
                   s.region_key, s.active, s.source
              FROM stores s
            """;

    private static final String CUSTOMER_COLUMNS = """
            SELECT c.id, c.code, c.name, c.segment, c.tier, c.agreed_discount_pct, c.typical_qty, c.profile, c.sla_days
              FROM customers c
            """;

    private final JdbcTemplate jdbc;

    CatalogueJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ProductRef readProduct(ResultSet rs, int i) throws SQLException {
        return new ProductRef(Sql.uuid(rs, "id"), rs.getString("item_number"), rs.getString("description"),
                rs.getString("short_name"), rs.getString("category"), rs.getString("subcategory"),
                rs.getString("commodity"), rs.getString("unit"), rs.getBoolean("has_sales"),
                rs.getString("default_store_code"), rs.getString("source"));
    }

    private static StoreRef readStore(ResultSet rs, int i) throws SQLException {
        return new StoreRef(Sql.uuid(rs, "id"), rs.getString("store_code"), rs.getString("legal_name"),
                rs.getString("country"), rs.getString("subdivision_code"), rs.getString("msa_name"),
                rs.getBigDecimal("rpp"), Sql.integer(rs, "txns"), rs.getString("segment"), rs.getString("region_key"),
                rs.getBoolean("active"), rs.getString("source"));
    }

    private static CustomerRef readCustomer(ResultSet rs, int i) throws SQLException {
        return new CustomerRef(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("segment"),
                rs.getString("tier"), rs.getBigDecimal("agreed_discount_pct"), rs.getInt("typical_qty"),
                rs.getString("profile"), rs.getInt("sla_days"));
    }

    @Override
    public Optional<ProductRef> product(String itemNumber) {
        if (itemNumber == null || itemNumber.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(PRODUCT_COLUMNS + " WHERE p.tenant_id = ? AND lower(p.item_number) = lower(?)",
                CatalogueJdbc::readProduct, Sql.tenant(), itemNumber.strip()));
    }

    @Override
    public Optional<ProductRef> productById(UUID id) {
        return first(jdbc.query(PRODUCT_COLUMNS + " WHERE p.tenant_id = ? AND p.id = ?",
                CatalogueJdbc::readProduct, Sql.tenant(), id));
    }

    @Override
    public List<ProductRef> products() {
        return jdbc.query(PRODUCT_COLUMNS + " WHERE p.tenant_id = ? ORDER BY p.item_number",
                CatalogueJdbc::readProduct, Sql.tenant());
    }

    @Override
    public Optional<StoreRef> store(String storeCode) {
        if (storeCode == null || storeCode.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(STORE_COLUMNS + " WHERE s.tenant_id = ? AND lower(s.store_code) = lower(?)",
                CatalogueJdbc::readStore, Sql.tenant(), storeCode.strip()));
    }

    @Override
    public Optional<StoreRef> storeById(UUID id) {
        return first(jdbc.query(STORE_COLUMNS + " WHERE s.tenant_id = ? AND s.id = ?",
                CatalogueJdbc::readStore, Sql.tenant(), id));
    }

    @Override
    public List<StoreRef> stores() {
        return jdbc.query(STORE_COLUMNS + " WHERE s.tenant_id = ? AND s.active ORDER BY s.store_code",
                CatalogueJdbc::readStore, Sql.tenant());
    }

    @Override
    public Optional<CustomerRef> customer(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return first(jdbc.query(CUSTOMER_COLUMNS + " WHERE c.tenant_id = ? AND lower(c.code) = lower(?)",
                CatalogueJdbc::readCustomer, Sql.tenant(), code.strip()));
    }

    @Override
    public List<CustomerRef> customers() {
        return jdbc.query(CUSTOMER_COLUMNS + " WHERE c.tenant_id = ? ORDER BY c.name",
                CatalogueJdbc::readCustomer, Sql.tenant());
    }

    @Override
    public List<RegionRef> regions() {
        return jdbc.query("SELECT region_key, label, short_label FROM regions WHERE country_code = ? ORDER BY position",
                (rs, i) -> new RegionRef(rs.getString("region_key"), rs.getString("label"), rs.getString("short_label")),
                country());
    }

    @Override
    public Optional<RegionRef> region(String key) {
        return regions().stream().filter(r -> r.key().equals(key)).findFirst();
    }

    @Override
    public String country() {
        UUID tenant = Sql.tenant();
        List<String> fromStores = jdbc.queryForList(
                "SELECT s.country FROM stores s WHERE s.tenant_id = ? AND s.active ORDER BY s.store_code LIMIT 1",
                String.class, tenant);
        if (!fromStores.isEmpty() && fromStores.get(0) != null) {
            return fromStores.get(0);
        }
        List<String> fromSettings = jdbc.queryForList(
                "SELECT country_code FROM tenant_settings WHERE tenant_id = ?", String.class, tenant);
        return fromSettings.isEmpty() || fromSettings.get(0) == null ? "US" : fromSettings.get(0);
    }

    @Override
    public CatalogueCoverage coverage() {
        UUID tenant = Sql.tenant();
        return jdbc.query("""
                SELECT (SELECT count(*) FROM products p WHERE p.tenant_id = ?) AS products,
                       (SELECT count(*) FROM stores s WHERE s.tenant_id = ? AND s.active) AS stores,
                       (SELECT count(*) FROM customers c WHERE c.tenant_id = ?) AS customers,
                       (SELECT count(*) FROM stores s WHERE s.tenant_id = ? AND s.active AND s.region_key = 'unassigned') AS stores_unassigned,
                       (SELECT count(*) FROM customers c WHERE c.tenant_id = ? AND c.segment = 'unassigned') AS customers_unassigned
                """, rs -> {
            rs.next();
            return new CatalogueCoverage(rs.getInt("products"), rs.getInt("stores"), rs.getInt("customers"),
                    rs.getInt("stores_unassigned"), rs.getInt("customers_unassigned"));
        }, tenant, tenant, tenant, tenant, tenant);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}

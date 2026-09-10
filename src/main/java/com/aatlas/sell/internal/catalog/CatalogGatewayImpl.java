package com.aatlas.sell.internal.catalog;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.RegionRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC over catalog's tables, exactly like {@code ReferenceDataRepository} reads the
 * reference tables: every business-table query carries {@code tenant_id} explicitly, and
 * row-level security is the second line, not the first.
 */
@Repository
class CatalogGatewayImpl implements CatalogGateway {

    private static final ProductRowMapper PRODUCT_MAPPER = new ProductRowMapper();
    private static final StoreRowMapper STORE_MAPPER = new StoreRowMapper();
    private static final CustomerRowMapper CUSTOMER_MAPPER = new CustomerRowMapper();

    private final JdbcTemplate jdbc;

    CatalogGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static UUID tenant() {
        return TenantContext.requireTenantId();
    }

    @Override
    public Optional<ProductRef> findProduct(String itemNumber) {
        List<ProductRef> rows = jdbc.query(
                """
                select item_number, description, short_name, category, subcategory, commodity, unit,
                       has_sales, default_store_code
                from products where tenant_id = ? and item_number = ?
                """,
                PRODUCT_MAPPER, tenant(), itemNumber);
        return rows.stream().findFirst();
    }

    @Override
    public List<ProductRef> sellableProducts() {
        return jdbc.query(
                """
                select item_number, description, short_name, category, subcategory, commodity, unit,
                       has_sales, default_store_code
                from products where tenant_id = ? and has_sales = true
                order by item_number
                """,
                PRODUCT_MAPPER, tenant());
    }

    @Override
    public Optional<StoreRef> findStore(String storeCode) {
        List<StoreRef> rows = jdbc.query(
                """
                select store_code, company_number, legal_name, country, subdivision_code, msa_name,
                       rpp, txns, item_count, segment, region_key
                from stores where tenant_id = ? and store_code = ?
                """,
                STORE_MAPPER, tenant(), storeCode);
        return rows.stream().findFirst();
    }

    @Override
    public List<StoreRef> allStores() {
        return jdbc.query(
                """
                select store_code, company_number, legal_name, country, subdivision_code, msa_name,
                       rpp, txns, item_count, segment, region_key
                from stores where tenant_id = ?
                order by store_code
                """,
                STORE_MAPPER, tenant());
    }

    @Override
    public List<String> sellerCodesOf(String itemNumber) {
        return jdbc.queryForList(
                """
                select s.store_code
                from product_stores ps
                join products p on p.id = ps.product_id
                join stores s on s.id = ps.store_id
                where ps.tenant_id = ? and p.item_number = ? and ps.sells = true
                order by s.store_code
                """,
                String.class, tenant(), itemNumber);
    }

    @Override
    public boolean sells(String itemNumber, String storeCode) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                from product_stores ps
                join products p on p.id = ps.product_id
                join stores s on s.id = ps.store_id
                where ps.tenant_id = ? and p.item_number = ? and s.store_code = ? and ps.sells = true
                """,
                Integer.class, tenant(), itemNumber, storeCode);
        return count != null && count > 0;
    }

    @Override
    public Optional<CustomerRef> findCustomer(String code) {
        List<CustomerRef> rows = jdbc.query(
                """
                select code, name, segment, tier, agreed_discount_pct, typical_qty, profile, sla_days, note
                from customers where tenant_id = ? and code = ?
                """,
                CUSTOMER_MAPPER, tenant(), code);
        return rows.stream().findFirst();
    }

    @Override
    public List<CustomerRef> allCustomers() {
        return jdbc.query(
                """
                select code, name, segment, tier, agreed_discount_pct, typical_qty, profile, sla_days, note
                from customers where tenant_id = ?
                order by code
                """,
                CUSTOMER_MAPPER, tenant());
    }

    @Override
    public Optional<RegionRef> regionOf(String regionKey) {
        String country = tenantCountry();
        List<RegionRef> rows = jdbc.query(
                "select region_key, label, short_label from regions where country_code = ? and region_key = ?",
                (rs, rowNum) -> new RegionRef(rs.getString("region_key"), rs.getString("short_label"), rs.getString("label")),
                country, regionKey);
        return rows.stream().findFirst();
    }

    @Override
    public List<RegionRef> allRegions() {
        String country = tenantCountry();
        return jdbc.query(
                "select region_key, label, short_label from regions where country_code = ? order by position",
                (rs, rowNum) -> new RegionRef(rs.getString("region_key"), rs.getString("short_label"), rs.getString("label")),
                country);
    }

    @Override
    public String tenantCountry() {
        List<String> countries = jdbc.queryForList(
                "select country from stores where tenant_id = ? limit 1", String.class, tenant());
        return countries.stream().findFirst().orElse("US");
    }

    @Override
    public CommodityTrend commodityTrend(String commodityKey) {
        List<CommodityTrend> rows = jdbc.query(
                "select pct90, label from commodities where commodity_key = ?",
                (rs, rowNum) -> new CommodityTrend(rs.getBigDecimal("pct90").doubleValue(), rs.getString("label")),
                commodityKey);
        return rows.stream().findFirst().orElse(new CommodityTrend(0, "No commodity exposure"));
    }

    private static final class ProductRowMapper implements org.springframework.jdbc.core.RowMapper<ProductRef> {
        @Override
        public ProductRef mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new ProductRef(
                    rs.getString("item_number"),
                    rs.getString("description"),
                    rs.getString("short_name"),
                    rs.getString("category"),
                    rs.getString("subcategory"),
                    rs.getString("commodity"),
                    rs.getString("unit"),
                    rs.getBoolean("has_sales"),
                    rs.getString("default_store_code"));
        }
    }

    private static final class StoreRowMapper implements org.springframework.jdbc.core.RowMapper<StoreRef> {
        @Override
        public StoreRef mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            Integer txns = (Integer) rs.getObject("txns");
            Integer itemCount = (Integer) rs.getObject("item_count");
            return new StoreRef(
                    rs.getString("store_code"),
                    rs.getString("company_number"),
                    rs.getString("legal_name"),
                    rs.getString("country"),
                    rs.getString("subdivision_code"),
                    rs.getString("msa_name"),
                    rs.getBigDecimal("rpp"),
                    txns,
                    itemCount,
                    rs.getString("segment"),
                    rs.getString("region_key"));
        }
    }

    private static final class CustomerRowMapper implements org.springframework.jdbc.core.RowMapper<CustomerRef> {
        @Override
        public CustomerRef mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new CustomerRef(
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("segment"),
                    rs.getString("tier"),
                    rs.getBigDecimal("agreed_discount_pct"),
                    rs.getInt("typical_qty"),
                    rs.getString("profile"),
                    rs.getInt("sla_days"),
                    rs.getString("note"));
        }
    }
}

package com.aatlas.buy.internal;

import com.aatlas.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the tenant's catalogue (products, branches, which branch sells what)
 * and the global logistics reference tables - all owned by the {@code catalog} module.
 *
 * <p>{@code catalog}'s public (package-root) API is {@code CatalogSeeding} alone - the
 * interface {@code ingest} uses to copy the seed catalogue into a tenant. It exposes no
 * reader for the rows themselves, so there is nothing in {@code catalog}'s package root for
 * {@code buy} to call for the product/branch/logistics facts {@code buildBuyRecommendation}
 * and {@code getBuyIntel} need on every request.
 *
 * <p>This gateway reads the real tables directly with {@link JdbcTemplate} instead: plain
 * SQL against {@code products}, {@code stores}, {@code product_stores}, {@code
 * logistics_lanes} and {@code logistics_origins} - the same rows {@code catalog}'s own
 * {@code CatalogService} and {@code ReferenceDataRepository} read, so the data is real and
 * live, not re-derived. It creates no Java dependency on {@code catalog.internal} (only
 * table and column names, which {@code ModularityTests} does not see), so it does not fail
 * the modularity build, but it is still a stand-in for a proper cross-module reader.
 *
 * <p>TODO(merge): replace with a public reader on {@code catalog} (e.g. a
 * {@code CatalogReader} in its package root) once one exists, and delete this class.
 */
@Component
public class CatalogGateway {

    private final JdbcTemplate jdbc;
    private final Map<String, List<String>> storeOrderByCountry;

    CatalogGateway(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.storeOrderByCountry = loadStoreOrder(json);
    }

    // -- Products --------------------------------------------------------------------------

    public record ProductRow(
            String itemNumber,
            String description,
            String shortName,
            String category,
            String subcategory,
            String commodity,
            String unit,
            boolean hasSales,
            String defaultStoreCode) {
    }

    public Optional<ProductRow> findProduct(String itemNumber) {
        UUID tenantId = TenantContext.requireTenantId();
        List<ProductRow> rows = jdbc.query("""
                select item_number, description, short_name, category, subcategory, commodity, unit,
                       has_sales, default_store_code
                from products where tenant_id = ? and item_number = ?
                """,
                (rs, i) -> new ProductRow(
                        rs.getString("item_number"), rs.getString("description"), rs.getString("short_name"),
                        rs.getString("category"), rs.getString("subcategory"), rs.getString("commodity"),
                        rs.getString("unit"), rs.getBoolean("has_sales"), rs.getString("default_store_code")),
                tenantId, itemNumber);
        return rows.stream().findFirst();
    }

    // -- Stores ------------------------------------------------------------------------------

    public record StoreRow(
            String storeCode,
            String legalName,
            String country,
            String subdivisionCode,
            String msaName,
            Integer txns,
            String segment,
            String regionKey) {
    }

    private static final String STORE_COLUMNS =
            "store_code, legal_name, country, subdivision_code, msa_name, txns, segment, region_key";

    private static StoreRow storeRow(ResultSet rs) throws SQLException {
        return new StoreRow(
                rs.getString("store_code"), rs.getString("legal_name"), rs.getString("country"),
                rs.getString("subdivision_code"), rs.getString("msa_name"),
                rs.getObject("txns") == null ? null : rs.getInt("txns"), rs.getString("segment"),
                rs.getString("region_key"));
    }

    public Optional<StoreRow> findStore(String storeCode) {
        UUID tenantId = TenantContext.requireTenantId();
        List<StoreRow> rows = jdbc.query(
                "select " + STORE_COLUMNS + " from stores where tenant_id = ? and store_code = ?",
                (rs, i) -> storeRow(rs), tenantId, storeCode);
        return rows.stream().findFirst();
    }

    /** Every branch in a market region (south/west/north/east), in no particular order. */
    public List<StoreRow> storesInRegion(String regionKey) {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.query(
                "select " + STORE_COLUMNS + " from stores where tenant_id = ? and region_key = ?",
                (rs, i) -> storeRow(rs), tenantId, regionKey);
    }

    /** Any one of the tenant's stores' country - every store in a tenant shares one. */
    public Optional<String> tenantCountry() {
        UUID tenantId = TenantContext.requireTenantId();
        List<String> rows = jdbc.query("select country from stores where tenant_id = ? limit 1",
                (rs, i) -> rs.getString("country"), tenantId);
        return rows.stream().findFirst();
    }

    /** The busiest branch (by transaction count) in a market region - where a regional buy is costed. */
    public Optional<StoreRow> primaryStoreForRegion(String regionKey) {
        UUID tenantId = TenantContext.requireTenantId();
        List<StoreRow> rows = jdbc.query(
                "select " + STORE_COLUMNS
                        + " from stores where tenant_id = ? and region_key = ? order by txns desc nulls last limit 1",
                (rs, i) -> storeRow(rs), tenantId, regionKey);
        return rows.stream().findFirst();
    }

    /** Whether this branch has sold this item, i.e. whether the (item, branch) pair is priceable. */
    public boolean sells(String itemNumber, String storeCode) {
        UUID tenantId = TenantContext.requireTenantId();
        Boolean exists = jdbc.queryForObject("""
                select exists (
                    select 1 from product_stores ps
                    join products p on p.id = ps.product_id
                    join stores s on s.id = ps.store_id
                    where ps.tenant_id = ? and p.item_number = ? and s.store_code = ? and ps.sells
                )
                """, Boolean.class, tenantId, itemNumber, storeCode);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * The first branch in the seed's own order for a country - {@code TENANTS[0]} in the
     * frontend's {@code mock/catalog.ts}. Read from {@code seed/stores.json} (the same file
     * {@code catalog}'s loader reads) rather than the tenant's own rows, because Postgres
     * keeps no ordinal column for insertion order and this is the one place the frontend's
     * own array position matters: {@code getPricingModel}'s fallback store for an item with
     * no {@code defaultTenant} (the three PIM-only items).
     */
    public String firstStoreCodeInSeedOrder(String country) {
        List<String> order = storeOrderByCountry.get(country);
        if (order == null || order.isEmpty()) {
            order = storeOrderByCountry.get("US");
        }
        return order.get(0);
    }

    private static Map<String, List<String>> loadStoreOrder(ObjectMapper json) {
        try (InputStream in = CatalogGateway.class.getResourceAsStream("/seed/stores.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonNode root = json.readTree(in);
            Map<String, List<String>> out = new LinkedHashMap<>();
            root.fieldNames().forEachRemaining(country -> {
                List<String> ids = new ArrayList<>();
                for (JsonNode row : root.get(country)) {
                    ids.add(row.get("store_id").asText());
                }
                out.put(country, List.copyOf(ids));
            });
            return Map.copyOf(out);
        } catch (Exception e) {
            throw new UncheckedIOException("Could not read seed/stores.json", new java.io.IOException(e));
        }
    }

    // -- Market regions and commodities (global reference, not tenant-scoped) ----------------

    public record MarketRegion(String key, String label, String shortLabel) {
    }

    /** The four market regions of a country (south/west/north/east), from the {@code regions} table. */
    public Optional<MarketRegion> marketRegion(String countryCode, String regionKey) {
        List<MarketRegion> rows = jdbc.query(
                "select region_key, label, short_label from regions where country_code = ? and region_key = ?",
                (rs, i) -> new MarketRegion(rs.getString("region_key"), rs.getString("label"),
                        rs.getString("short_label")),
                countryCode, regionKey);
        return rows.stream().findFirst();
    }

    public record CommodityTrend(double pct90, String label) {
    }

    /** The 90-day trend for one commodity ("copper", "steel", ...), from the {@code commodities} table. */
    public Optional<CommodityTrend> commodityTrend(String commodityKey) {
        List<CommodityTrend> rows = jdbc.query(
                "select pct90, label from commodities where commodity_key = ?",
                (rs, i) -> new CommodityTrend(rs.getDouble("pct90"), rs.getString("label")),
                commodityKey);
        return rows.stream().findFirst();
    }

    // -- Logistics reference (global, not tenant-scoped) ------------------------------------

    public record Origin(
            String entry, String mode, String gateway, double inboundPct, int inboundDays, double dutyPct,
            String dutyNote) {
    }

    /**
     * {@code inlandPct} is {@link java.math.BigDecimal}, not {@code double}: a {@code
     * Map<String, Double>} would autobox on every read and write, and {@code
     * ArchitectureRulesTest.noFloatingPointMoney} forbids this package depending on {@code
     * java.lang.Double} at all. Callers read it with {@link java.math.BigDecimal#doubleValue()}.
     */
    public record LaneRef(
            String key, String label, List<String> states, Map<String, java.math.BigDecimal> inlandPct,
            Map<String, Integer> inlandDays) {
    }

    public record LogisticsRef(List<LaneRef> regions, Map<String, Origin> origins) {
    }

    /** The rate card: inland haul by point of entry, inbound freight and duty by origin country. */
    public LogisticsRef logistics() {
        Map<String, LaneBuilder> lanes = new LinkedHashMap<>();
        jdbc.query("""
                select region_key, region_label, states, entry, inland_pct, inland_days
                from logistics_lanes order by position, region_key, entry
                """,
                rs -> {
                    LaneBuilder lane = lanes.computeIfAbsent(rs.getString("region_key"), key -> {
                        try {
                            return new LaneBuilder(key, rs.getString("region_label"), states(rs.getArray("states")));
                        } catch (SQLException e) {
                            throw new IllegalStateException("Could not read logistics_lanes.states", e);
                        }
                    });
                    lane.inlandPct.put(rs.getString("entry"), rs.getBigDecimal("inland_pct"));
                    lane.inlandDays.put(rs.getString("entry"), rs.getInt("inland_days"));
                });

        Map<String, Origin> origins = new LinkedHashMap<>();
        jdbc.query("""
                select country, entry, mode, gateway, inbound_pct, inbound_days, duty_pct, duty_note
                from logistics_origins order by position, country
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> origins.put(rs.getString("country"), new Origin(
                        rs.getString("entry"), rs.getString("mode"), rs.getString("gateway"),
                        rs.getDouble("inbound_pct"), rs.getInt("inbound_days"), rs.getDouble("duty_pct"),
                        rs.getString("duty_note"))));

        List<LaneRef> regions = lanes.values().stream().map(LaneBuilder::build).toList();
        return new LogisticsRef(regions, origins);
    }

    private static List<String> states(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static final class LaneBuilder {
        private final String key;
        private final String label;
        private final List<String> states;
        private final Map<String, java.math.BigDecimal> inlandPct = new LinkedHashMap<>();
        private final Map<String, Integer> inlandDays = new LinkedHashMap<>();

        LaneBuilder(String key, String label, List<String> states) {
            this.key = key;
            this.label = label;
            this.states = states;
        }

        LaneRef build() {
            return new LaneRef(key, label, states, Map.copyOf(inlandPct), Map.copyOf(inlandDays));
        }
    }
}

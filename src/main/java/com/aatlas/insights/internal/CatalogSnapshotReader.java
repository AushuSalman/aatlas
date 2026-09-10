package com.aatlas.insights.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Loads a {@link CatalogSnapshot} for the current tenant: the tenant-scoped catalogue and
 * supplier rows wave 1 seeded, plus the shared reference tables (market regions, commodity
 * trend, the freight-lane rate card) wave 1 loaded once at startup.
 *
 * <p>Plain JDBC against the same tables {@code catalog} and {@code suppliers} own, not a
 * call into either module: neither publishes a reader in its public API yet (only a
 * seeding interface each - {@code CatalogSeeding}, {@code SupplierPanelSeeder}), and
 * {@code ModularityTests} forbids reaching into their {@code internal} packages. This is
 * the same "read the physical rows, not another module's Java API" approach the wave-2
 * brief describes for the pure-function engines themselves.
 */
@Component
class CatalogSnapshotReader {

    private final JdbcTemplate jdbc;

    CatalogSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CatalogSnapshot load() {
        UUID tenantId = TenantContext.requireTenantId();

        List<ProductRef> products = jdbc.query(
                """
                select item_number, description, short_name, category, subcategory, commodity, unit,
                       has_sales, default_store_code
                from products where tenant_id = ? order by item_number
                """,
                (rs, i) -> new ProductRef(
                        rs.getString("item_number"),
                        rs.getString("description"),
                        rs.getString("short_name"),
                        rs.getString("category"),
                        rs.getString("subcategory"),
                        rs.getString("commodity"),
                        rs.getString("unit"),
                        rs.getBoolean("has_sales"),
                        rs.getString("default_store_code")),
                tenantId);

        if (products.isEmpty()) {
            throw noCatalogue();
        }

        Map<String, StoreRef> stores = new LinkedHashMap<>();
        jdbc.query(
                """
                select id, store_code, legal_name, country, subdivision_code, msa_name, rpp, txns, segment, region_key
                from stores where tenant_id = ? order by store_code
                """,
                (RowCallbackHandler) rs -> {
                    StoreRef store = new StoreRef(
                            (UUID) rs.getObject("id"),
                            rs.getString("store_code"),
                            rs.getString("legal_name"),
                            rs.getString("country"),
                            rs.getString("subdivision_code"),
                            rs.getString("msa_name"),
                            rs.getBigDecimal("rpp"),
                            rs.getObject("txns") == null ? null : rs.getInt("txns"),
                            rs.getString("segment"),
                            rs.getString("region_key"));
                    stores.put(store.storeCode(), store);
                },
                tenantId);

        Map<String, Set<String>> sellers = new LinkedHashMap<>();
        jdbc.query(
                """
                select p.item_number as item_number, s.store_code as store_code
                from product_stores ps
                join products p on p.id = ps.product_id
                join stores s on s.id = ps.store_id
                where ps.tenant_id = ? and ps.sells = true
                """,
                (RowCallbackHandler) rs -> sellers.computeIfAbsent(rs.getString("item_number"), k -> new LinkedHashSet<>())
                        .add(rs.getString("store_code")),
                tenantId);

        List<SupplierRef> suppliers = jdbc.query(
                """
                select supplier_key, name, country, lead_time_days, otif_pct, price_index
                from suppliers where tenant_id = ? and is_custom = false order by supplier_key
                """,
                (rs, i) -> new SupplierRef(
                        rs.getString("supplier_key"),
                        rs.getString("name"),
                        rs.getString("country"),
                        rs.getInt("lead_time_days"),
                        rs.getDouble("otif_pct"),
                        rs.getDouble("price_index")),
                tenantId);

        Map<String, CommodityRef> commodities = new LinkedHashMap<>();
        jdbc.query("select commodity_key, pct90, label from commodities", (RowCallbackHandler) rs ->
                commodities.put(rs.getString("commodity_key"),
                        new CommodityRef(rs.getBigDecimal("pct90"), rs.getString("label"))));

        // The tenant's own country decides which market-region / logistics constants apply -
        // read from its branches, exactly as CatalogService.regions() does.
        String country = stores.values().stream()
                .map(StoreRef::country)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .findFirst()
                .orElse("US");

        Map<String, List<String>> statesByRegion = new LinkedHashMap<>();
        Map<String, String> subdivisionNames = new LinkedHashMap<>();
        jdbc.query(
                "select region_key, code, name from subdivisions where country_code = ? order by region_key, position",
                (RowCallbackHandler) rs -> {
                    statesByRegion.computeIfAbsent(rs.getString("region_key"), k -> new ArrayList<>())
                            .add(rs.getString("code"));
                    subdivisionNames.put(rs.getString("code"), rs.getString("name"));
                },
                country);

        List<MarketRegionRef> marketRegions = jdbc.query(
                "select region_key, label, short_label from regions where country_code = ? order by position",
                (rs, i) -> new MarketRegionRef(
                        rs.getString("region_key"),
                        rs.getString("short_label"),
                        rs.getString("label"),
                        List.copyOf(statesByRegion.getOrDefault(rs.getString("region_key"), List.of()))),
                country);

        Map<String, LaneBuilder> lanes = new LinkedHashMap<>();
        jdbc.query(
                """
                select region_key, region_label, states, entry, inland_pct, inland_days
                from logistics_lanes order by position, region_key, entry
                """,
                (RowCallbackHandler) rs -> {
                    LaneBuilder lane = lanes.computeIfAbsent(rs.getString("region_key"), key -> {
                        try {
                            return new LaneBuilder(key, rs.getString("region_label"), states(rs.getArray("states")));
                        } catch (SQLException ex) {
                            throw new IllegalStateException("Could not read logistics_lanes.states", ex);
                        }
                    });
                    lane.inlandPct.put(rs.getString("entry"), rs.getBigDecimal("inland_pct"));
                    lane.inlandDays.put(rs.getString("entry"), rs.getInt("inland_days"));
                });
        List<LogisticsLaneRef> logisticsLanes = lanes.values().stream().map(LaneBuilder::build).toList();

        Map<String, LogisticsOriginRef> origins = new LinkedHashMap<>();
        jdbc.query(
                "select country, entry, mode, gateway, inbound_pct, inbound_days, duty_pct from logistics_origins",
                (RowCallbackHandler) rs -> origins.put(rs.getString("country"), new LogisticsOriginRef(
                        rs.getString("entry"), rs.getString("mode"), rs.getString("gateway"),
                        rs.getBigDecimal("inbound_pct"), rs.getInt("inbound_days"), rs.getBigDecimal("duty_pct"))));

        return new CatalogSnapshot(
                products, stores, sellers, suppliers, commodities, marketRegions, subdivisionNames,
                logisticsLanes, origins);
    }

    static ApiException noCatalogue() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_catalogue",
                "This workspace has no catalogue yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen.");
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
        private final Map<String, BigDecimal> inlandPct = new LinkedHashMap<>();
        private final Map<String, Integer> inlandDays = new LinkedHashMap<>();

        LaneBuilder(String key, String label, List<String> states) {
            this.key = key;
            this.label = label;
            this.states = states;
        }

        LogisticsLaneRef build() {
            return new LogisticsLaneRef(key, label, states, Map.copyOf(inlandPct), Map.copyOf(inlandDays));
        }
    }
}

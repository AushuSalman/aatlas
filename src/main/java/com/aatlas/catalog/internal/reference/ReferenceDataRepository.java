package com.aatlas.catalog.internal.reference;

import com.aatlas.common.cache.CacheNames;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads of the reference tables, shaped exactly as the frontend's constants.
 *
 * <p>Plain JDBC rather than JPA: these tables have natural composite keys, no tenant, no
 * version column and no writer other than the loader, so an entity would be ceremony.
 *
 * <p>Cached in the in-process reference tier ({@link CacheNames#REFERENCE}). The rows are
 * identical for every tenant and change only when a seed file or a rate card does, and
 * the loader is the only writer, so a 12-hour TTL is the whole invalidation story.
 */
@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    public ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A country's market regions with their subdivisions, in seed order. */
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager", key = "'regions:' + #countryCode")
    public List<MarketRegion> regionsOf(String countryCode) {
        Map<String, List<Subdivision>> subdivisionsByRegion = new LinkedHashMap<>();
        jdbc.query("""
                select region_key, code, name from subdivisions
                where country_code = ? order by region_key, position
                """,
                rs -> {
                    subdivisionsByRegion
                            .computeIfAbsent(rs.getString("region_key"), unused -> new ArrayList<>())
                            .add(new Subdivision(rs.getString("code"), rs.getString("name")));
                },
                countryCode);

        return jdbc.query("""
                select region_key, label, short_label from regions
                where country_code = ? order by position
                """,
                (rs, rowNum) -> {
                    String key = rs.getString("region_key");
                    return new MarketRegion(
                            key,
                            rs.getString("label"),
                            rs.getString("short_label"),
                            List.copyOf(subdivisionsByRegion.getOrDefault(key, List.of())));
                },
                countryCode);
    }

    /** The rate card: {@code {regions, origins}} as the frontend's {@code REGIONS} and {@code ORIGINS}. */
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager", key = "'logistics'")
    public Logistics logistics() {
        Map<String, LaneBuilder> lanes = new LinkedHashMap<>();
        jdbc.query("""
                select region_key, region_label, states, entry, inland_pct, inland_days
                from logistics_lanes order by position, region_key, entry
                """,
                rs -> {
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

        Map<String, Origin> origins = new LinkedHashMap<>();
        jdbc.query("""
                select country, entry, mode, gateway, inbound_pct, inbound_days, duty_pct, duty_note
                from logistics_origins order by position, country
                """,
                rs -> {
                    origins.put(rs.getString("country"), new Origin(
                            rs.getString("entry"),
                            rs.getString("mode"),
                            rs.getString("gateway"),
                            rs.getBigDecimal("inbound_pct"),
                            rs.getInt("inbound_days"),
                            rs.getBigDecimal("duty_pct"),
                            rs.getString("duty_note")));
                });

        List<Lane> regions = lanes.values().stream().map(LaneBuilder::build).toList();
        return new Logistics(regions, origins);
    }

    /** The 90-day trend for one commodity, if the reference knows it. */
    // Own prefix: history's ReferenceJdbc.commodity shares this cache under 'commodity:' with a
    // different value type, and one key for two types is a ClassCastException on the second read.
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager",
            key = "'commodity-trend:' + #commodity")
    public Optional<CommodityTrend> commodityTrend(String commodity) {
        List<CommodityTrend> rows = jdbc.query(
                "select pct90, label from commodities where commodity_key = ?",
                (rs, rowNum) -> new CommodityTrend(rs.getBigDecimal("pct90"), rs.getString("label")),
                commodity);
        return rows.stream().findFirst();
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

        Lane build() {
            return new Lane(key, label, states, Map.copyOf(inlandPct), Map.copyOf(inlandDays));
        }
    }

    // ---- shapes ------------------------------------------------------------------------

    /** One of the four market regions of a country, with what it covers. */
    public record MarketRegion(String key, String label, String shortLabel, List<Subdivision> subdivisions) {

        public List<String> codes() {
            return subdivisions.stream().map(Subdivision::code).toList();
        }
    }

    public record Subdivision(String code, String name) {
    }

    /** The frontend's {@code Region} in {@code logistics.ts}. */
    public record Lane(
            String key,
            String label,
            List<String> states,
            Map<String, BigDecimal> inlandPct,
            Map<String, Integer> inlandDays) {
    }

    /** The frontend's {@code Origin} in {@code logistics.ts}. */
    public record Origin(
            String entry,
            String mode,
            String gateway,
            BigDecimal inboundPct,
            int inboundDays,
            BigDecimal dutyPct,
            String dutyNote) {
    }

    /** {@code {regions, origins}}, verbatim. */
    public record Logistics(List<Lane> regions, Map<String, Origin> origins) {
    }

    /** The frontend's {@code COMMODITY_TREND} entry. */
    public record CommodityTrend(BigDecimal pct90, String label) {
    }
}

package com.aatlas.history.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.history.Reference;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reference tables and the tenant's guardrails, read the one way every engine reads
 * them. The global rows (benchmarks, commodities, origins, lanes) live in the in-process
 * reference cache; guardrails are a single primary-key read per tenant and are not cached
 * here, so an edit shows on the next request.
 */
@Repository
class ReferenceJdbc implements Reference {

    /** seed/guardrails.json, for a tenant that never saved its own. */
    static final Guardrails DEFAULT_GUARDRAILS = new Guardrails(
            new BigDecimal("25"), new BigDecimal("15"), new BigDecimal("8"), new BigDecimal("10"));

    /** The benchmark for a category no seed row covers: the distributor-wide band. */
    static final Benchmark DEFAULT_BENCHMARK = new Benchmark("*", "", new BigDecimal("30"), new BigDecimal("22"),
            new BigDecimal("38"), "Typical US plumbing/HVAC distributor gross-margin band. Reference data, not your numbers.",
            "default");

    /** A country the rate card does not list: an ocean lane with a middling inbound cost and a flat duty. */
    static final Origin FALLBACK_ORIGIN = new Origin("unknown", "west", "ocean", "estimated", new BigDecimal("5"), 30,
            new BigDecimal("5"), "Estimated: no rate card entry for this origin", true);

    static final String NONE_COMMODITY = "none";

    private final JdbcTemplate jdbc;

    ReferenceJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Guardrails guardrails() {
        List<Guardrails> rows = jdbc.query("""
                SELECT min_margin_pct, max_discount_pct, max_speed_premium_pct, max_market_deviation_pct
                  FROM pricing_guardrails WHERE tenant_id = ?
                """, (rs, i) -> new Guardrails(rs.getBigDecimal("min_margin_pct"), rs.getBigDecimal("max_discount_pct"),
                rs.getBigDecimal("max_speed_premium_pct"), rs.getBigDecimal("max_market_deviation_pct")), Sql.tenant());
        return rows.isEmpty() ? DEFAULT_GUARDRAILS : rows.get(0);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager",
            key = "'benchmark:' + #category + '/' + #subcategory")
    public Benchmark benchmark(String category, String subcategory) {
        String cat = category == null ? "" : category.strip();
        String sub = subcategory == null ? "" : subcategory.strip();
        if (!cat.isEmpty() && !sub.isEmpty()) {
            Benchmark exact = benchmarkRow(cat, sub, "subcategory");
            if (exact != null) {
                return exact;
            }
        }
        if (!cat.isEmpty()) {
            Benchmark byCategory = benchmarkRow(cat, "", "category");
            if (byCategory != null) {
                return byCategory;
            }
        }
        Benchmark fallback = benchmarkRow("*", "", "default");
        return fallback != null ? fallback : DEFAULT_BENCHMARK;
    }

    private Benchmark benchmarkRow(String category, String subcategory, String matched) {
        List<Benchmark> rows = jdbc.query("""
                SELECT category, subcategory, target_margin_pct, low_margin_pct, high_margin_pct, note
                  FROM pricing_benchmarks WHERE category = ? AND subcategory = ?
                """, (rs, i) -> new Benchmark(rs.getString("category"), rs.getString("subcategory"),
                rs.getBigDecimal("target_margin_pct"), rs.getBigDecimal("low_margin_pct"),
                rs.getBigDecimal("high_margin_pct"), rs.getString("note"), matched), category, subcategory);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager", key = "'commodity:' + #key")
    public Commodity commodity(String key) {
        String wanted = key == null || key.isBlank() ? NONE_COMMODITY : key.strip();
        List<Commodity> rows = commodityRows(wanted);
        if (rows.isEmpty() && !NONE_COMMODITY.equals(wanted)) {
            rows = commodityRows(NONE_COMMODITY);
        }
        return rows.isEmpty() ? new Commodity(NONE_COMMODITY, "No commodity exposure", BigDecimal.ZERO, null) : rows.get(0);
    }

    private List<Commodity> commodityRows(String key) {
        return jdbc.query("SELECT commodity_key, label, pct90, as_of FROM commodities WHERE commodity_key = ?",
                (rs, i) -> new Commodity(rs.getString("commodity_key"), rs.getString("label"),
                        rs.getBigDecimal("pct90"), Sql.date(rs, "as_of")), key);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager", key = "'origin:' + #country")
    public Origin origin(String country) {
        if (country == null || country.isBlank()) {
            return FALLBACK_ORIGIN;
        }
        List<Origin> rows = jdbc.query("""
                SELECT country, entry, mode, gateway, inbound_pct, inbound_days, duty_pct, duty_note
                  FROM logistics_origins WHERE lower(country) = lower(?)
                """, (rs, i) -> new Origin(rs.getString("country"), rs.getString("entry"), rs.getString("mode"),
                rs.getString("gateway"), rs.getBigDecimal("inbound_pct"), rs.getInt("inbound_days"),
                rs.getBigDecimal("duty_pct"), rs.getString("duty_note"), false), country.strip());
        if (rows.isEmpty()) {
            return new Origin(country.strip(), FALLBACK_ORIGIN.entry(), FALLBACK_ORIGIN.mode(), FALLBACK_ORIGIN.gateway(),
                    FALLBACK_ORIGIN.inboundPct(), FALLBACK_ORIGIN.inboundDays(), FALLBACK_ORIGIN.dutyPct(),
                    FALLBACK_ORIGIN.dutyNote(), true);
        }
        return rows.get(0);
    }

    @Override
    @Cacheable(cacheNames = CacheNames.REFERENCE, cacheManager = "referenceCacheManager",
            key = "'lane:' + #subdivisionCode + '/' + #entry")
    public Optional<Lane> lane(String subdivisionCode, String entry) {
        if (subdivisionCode == null || entry == null) {
            return Optional.empty();
        }
        List<Lane> rows = jdbc.query("""
                SELECT region_key, region_label, inland_pct, inland_days
                  FROM logistics_lanes WHERE entry = ? AND ? = ANY (states)
                 ORDER BY position LIMIT 1
                """, (rs, i) -> new Lane(rs.getString("region_key"), rs.getString("region_label"),
                rs.getBigDecimal("inland_pct"), rs.getInt("inland_days")), entry.strip(), subdivisionCode.strip());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}

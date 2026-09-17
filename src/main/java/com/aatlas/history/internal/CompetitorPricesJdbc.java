package com.aatlas.history.internal;

import com.aatlas.history.Anchor;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code competitor_prices}: the latest observation per competitor within the last
 * {@value #MAX_AGE_DAYS} days, store- or region-matched rows preferred.
 */
@Repository
class CompetitorPricesJdbc implements CompetitorPrices {

    private final NamedParameterJdbcTemplate jdbc;

    CompetitorPricesJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    private static MapSqlParameterSource recent(LocalDate today) {
        return Sql.params().addValue("today", today).addValue("since", today.minusDays(MAX_AGE_DAYS));
    }

    @Override
    public List<Observation> forItem(UUID productId, String regionKeyOrNull, UUID storeIdOrNull, LocalDate today) {
        MapSqlParameterSource params = recent(today).addValue("p", productId);
        List<String> matches = new ArrayList<>();
        if (storeIdOrNull != null) {
            matches.add("coalesce(cp.store_id = :s, false)");
            params.addValue("s", storeIdOrNull);
        }
        if (regionKeyOrNull != null && !regionKeyOrNull.isBlank()) {
            matches.add("coalesce(cp.region_key = :r, false)");
            params.addValue("r", regionKeyOrNull);
        }
        // DESC sorts NULLs first, hence the coalesce: an unmatched row must never outrank a matched one.
        String matched = matches.isEmpty() ? "false" : String.join(" OR ", matches);
        StringBuilder order = new StringBuilder("lower(cp.competitor)");
        for (String m : matches) {
            order.append(", ").append(m).append(" DESC");
        }
        order.append(", cp.observed_at DESC, cp.created_at DESC");
        return jdbc.query("SELECT DISTINCT ON (lower(cp.competitor)) cp.competitor, cp.price, cp.observed_at,"
                + " cp.region_key, cp.store_id, cp.source_url, (" + matched + ") AS matched"
                + " FROM competitor_prices cp WHERE cp.tenant_id = :t AND cp.product_id = :p"
                + " AND cp.observed_at BETWEEN :since AND :today ORDER BY " + order, params,
                (rs, i) -> new Observation(rs.getString("competitor"), Sql.money(rs, "price"),
                        Sql.date(rs, "observed_at"), rs.getString("region_key"), Sql.uuid(rs, "store_id"),
                        rs.getString("source_url"), rs.getBoolean("matched")));
    }

    @Override
    public Optional<Anchor> anchor(UUID productId, String regionKeyOrNull, UUID storeIdOrNull, LocalDate today) {
        List<Observation> all = forItem(productId, regionKeyOrNull, storeIdOrNull, today);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        List<Observation> matched = all.stream().filter(Observation::matched).toList();
        List<Observation> basis = matched.isEmpty() ? all : matched;
        double[] prices = basis.stream().mapToDouble(o -> o.price().doubleValue()).toArray();
        BigDecimal median = BigDecimal.valueOf(Stats.quantile(prices, 0.5)).setScale(Sql.SCALE, RoundingMode.HALF_UP);
        return Optional.of(new Anchor(median, Anchor.COMPETITOR, basis.size()));
    }

    @Override
    public Map<UUID, Anchor> medians(LocalDate today) {
        Map<UUID, Anchor> out = new HashMap<>();
        jdbc.query("""
                SELECT x.product_id, count(*) AS n,
                       round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY x.price) AS numeric), 4) AS median
                  FROM (SELECT DISTINCT ON (cp.product_id, lower(cp.competitor)) cp.product_id, cp.price
                          FROM competitor_prices cp
                         WHERE cp.tenant_id = :t AND cp.observed_at BETWEEN :since AND :today
                         ORDER BY cp.product_id, lower(cp.competitor), cp.observed_at DESC, cp.created_at DESC) x
                 GROUP BY x.product_id
                """, recent(today), rs -> {
            out.put(Sql.uuid(rs, "product_id"), new Anchor(Sql.money(rs, "median"), Anchor.COMPETITOR, rs.getInt("n")));
        });
        return out;
    }

    @Override
    public CompetitorCoverage coverage(LocalDate today) {
        return jdbc.query("""
                SELECT count(DISTINCT cp.product_id) AS items, count(*) AS observations,
                       count(DISTINCT lower(cp.competitor)) AS competitors
                  FROM competitor_prices cp
                 WHERE cp.tenant_id = :t AND cp.observed_at BETWEEN :since AND :today
                """, recent(today), rs -> {
            rs.next();
            return new CompetitorCoverage(rs.getInt("items"), rs.getInt("observations"), rs.getInt("competitors"));
        });
    }
}

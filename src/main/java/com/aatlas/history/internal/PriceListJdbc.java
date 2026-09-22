package com.aatlas.history.internal;

import com.aatlas.history.Inventory;
import com.aatlas.history.PriceList;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code product_prices}: the current list price and cost per item, each the latest
 * effective row carrying that component, store-specific winning over tenant-wide.
 */
@Repository
class PriceListJdbc implements PriceList {

    private final NamedParameterJdbcTemplate jdbc;

    PriceListJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** A half-built current price: one component from one row. */
    private record Component(BigDecimal value, String source, LocalDate from, boolean specific, String basis) {
    }

    /**
     * At a branch: its own rows win over tenant-wide ones. Tenant-wide: a tenant-wide row wins,
     * else the product's most recent branch row's branch stands in for both components (never
     * one branch's price next to another's cost), flagged {@code specific} so the ladder keeps
     * the stand-in from answering for a different branch. A products import writes only branch
     * rows, and a tenant-wide read must still see them.
     */
    private static String componentSql(String component, boolean product, boolean store) {
        String own = product ? " AND pp.product_id = :p" : "";
        String select = "SELECT DISTINCT ON (pp.product_id) pp.product_id, pp." + component + " AS val, pp.source,"
                + " pp.effective_from, pp.store_id IS NOT NULL AS specific, pp.basis::text AS basis";
        String carries = " AND pp.effective_from <= :today AND pp." + component + " IS NOT NULL AND pp." + component
                + " > 0";
        if (store) {
            return select + " FROM product_prices pp WHERE pp.tenant_id = :t" + own
                    + " AND (pp.store_id = :s OR pp.store_id IS NULL)" + carries
                    + " ORDER BY pp.product_id, (pp.store_id IS NOT NULL) DESC, pp.effective_from DESC, pp.created_at DESC";
        }
        return "WITH fallback AS (SELECT DISTINCT ON (pp.product_id) pp.product_id, pp.store_id"
                + " FROM product_prices pp WHERE pp.tenant_id = :t" + own
                + " AND pp.store_id IS NOT NULL AND pp.effective_from <= :today"
                + " ORDER BY pp.product_id, pp.effective_from DESC, pp.created_at DESC) "
                + select + " FROM product_prices pp LEFT JOIN fallback fb ON fb.product_id = pp.product_id"
                + " WHERE pp.tenant_id = :t" + own
                + " AND (pp.store_id IS NULL OR pp.store_id = fb.store_id)" + carries
                + " ORDER BY pp.product_id, (pp.store_id IS NULL) DESC, pp.effective_from DESC, pp.created_at DESC";
    }

    private static Component readComponent(ResultSet rs) throws SQLException {
        return new Component(Sql.money(rs, "val"), rs.getString("source"), Sql.date(rs, "effective_from"),
                rs.getBoolean("specific"), rs.getString("basis"));
    }

    private static CurrentPrice merge(Component list, Component cost) {
        if (list == null && cost == null) {
            return null;
        }
        return new CurrentPrice(
                list == null ? null : list.value(), list == null ? null : list.source(),
                list == null ? null : list.from(), list != null && list.specific(),
                cost == null ? null : cost.value(), cost == null ? null : cost.source(),
                cost == null ? null : cost.from(), cost != null && cost.specific(),
                list != null ? list.basis() : cost.basis());
    }

    @Override
    public Optional<CurrentPrice> current(UUID productId, UUID storeIdOrNull, LocalDate today) {
        MapSqlParameterSource params = Sql.params().addValue("p", productId).addValue("today", today);
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        boolean store = storeIdOrNull != null;
        Component list = jdbc.query(componentSql("list_price", true, store), params,
                rs -> rs.next() ? readComponent(rs) : null);
        Component cost = jdbc.query(componentSql("cost", true, store), params,
                rs -> rs.next() ? readComponent(rs) : null);
        return Optional.ofNullable(merge(list, cost));
    }

    @Override
    public Map<UUID, CurrentPrice> currentForStore(UUID storeIdOrNull, LocalDate today) {
        MapSqlParameterSource params = Sql.params().addValue("today", today);
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        boolean store = storeIdOrNull != null;
        Map<UUID, Component> lists = new HashMap<>();
        jdbc.query(componentSql("list_price", false, store), params, rs -> {
            lists.put(Sql.uuid(rs, "product_id"), readComponent(rs));
        });
        Map<UUID, Component> costs = new HashMap<>();
        jdbc.query(componentSql("cost", false, store), params, rs -> {
            costs.put(Sql.uuid(rs, "product_id"), readComponent(rs));
        });
        Map<UUID, CurrentPrice> out = new HashMap<>();
        for (UUID productId : union(lists, costs)) {
            out.put(productId, merge(lists.get(productId), costs.get(productId)));
        }
        return out;
    }

    /**
     * Every store-specific current row, keyed by (product, store), in one pass per
     * component. Tenant-wide rows (and the branch rows standing in for them) are
     * {@link #currentForStore} with a null store; the ladder layers the two.
     */
    Map<Inventory.PairKey, CurrentPrice> currentStoreSpecific(LocalDate today) {
        MapSqlParameterSource params = Sql.params().addValue("today", today);
        Map<Inventory.PairKey, Component> lists = new HashMap<>();
        jdbc.query(pairSql("list_price"), params, rs -> {
            lists.put(new Inventory.PairKey(Sql.uuid(rs, "product_id"), Sql.uuid(rs, "store_id")), readComponent(rs));
        });
        Map<Inventory.PairKey, Component> costs = new HashMap<>();
        jdbc.query(pairSql("cost"), params, rs -> {
            costs.put(new Inventory.PairKey(Sql.uuid(rs, "product_id"), Sql.uuid(rs, "store_id")), readComponent(rs));
        });
        Map<Inventory.PairKey, CurrentPrice> out = new HashMap<>();
        for (Inventory.PairKey key : union(lists, costs)) {
            out.put(key, merge(lists.get(key), costs.get(key)));
        }
        return out;
    }

    private static String pairSql(String component) {
        return "SELECT DISTINCT ON (pp.product_id, pp.store_id) pp.product_id, pp.store_id, pp." + component
                + " AS val, pp.source, pp.effective_from, true AS specific, pp.basis::text AS basis"
                + " FROM product_prices pp WHERE pp.tenant_id = :t AND pp.store_id IS NOT NULL"
                + " AND pp.effective_from <= :today AND pp." + component + " IS NOT NULL AND pp." + component + " > 0"
                + " ORDER BY pp.product_id, pp.store_id, pp.effective_from DESC, pp.created_at DESC";
    }

    private static <K> java.util.Set<K> union(Map<K, ?> a, Map<K, ?> b) {
        java.util.Set<K> keys = new java.util.LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    @Override
    public Set<UUID> pricedProducts(LocalDate today) {
        Set<UUID> out = new HashSet<>();
        jdbc.query("""
                SELECT DISTINCT pp.product_id FROM product_prices pp
                 WHERE pp.tenant_id = :t AND pp.effective_from <= :today AND pp.list_price > 0
                """, Sql.params().addValue("today", today), rs -> {
            out.add(Sql.uuid(rs, "product_id"));
        });
        return out;
    }

    @Override
    public PriceCoverage coverage(LocalDate today) {
        return jdbc.query("""
                SELECT count(DISTINCT pp.product_id) FILTER (WHERE pp.list_price IS NOT NULL AND pp.list_price > 0) AS with_price,
                       count(DISTINCT pp.product_id) FILTER (WHERE pp.cost IS NOT NULL AND pp.cost > 0) AS with_cost,
                       (SELECT count(*) FROM supplier_products sp
                         WHERE sp.tenant_id = :t AND sp.ex_works IS NOT NULL AND sp.ex_works > 0) AS quotes
                  FROM product_prices pp
                 WHERE pp.tenant_id = :t AND pp.effective_from <= :today
                """, Sql.params().addValue("today", today), rs -> {
            rs.next();
            return new PriceCoverage(rs.getInt("with_price"), rs.getInt("with_cost"), rs.getInt("quotes"));
        });
    }
}

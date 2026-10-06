package com.aatlas.history.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.history.MarginProfiles;
import com.aatlas.history.PricingMath;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MarginProfiles} over {@code sales_transactions}: one row per item sold in the twelve
 * months, with its realised margin. Lines carrying their own cost give the margin and its low
 * end; an item whose lines carry none is measured against its cost on the price list
 * ({@code product_prices.cost}, the tenant-wide row first), so a sales file without costs
 * still teaches the margin.
 */
@Repository
class MarginProfilesJdbc implements MarginProfiles {

    private final NamedParameterJdbcTemplate jdbc;

    MarginProfilesJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.SALES_HISTORY, key = "T(com.aatlas.common.cache.CacheNames).key("
            + "T(com.aatlas.common.tenant.TenantContext).requireTenantId(), 'marginProfile', #today)")
    public Profile profile(LocalDate today) {
        Window w = Window.trailingMonths(today, 12);
        return new Profile(today, jdbc.query("""
                WITH s AS (
                    SELECT st.product_id,
                           count(*) AS lines,
                           sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0)
                               / nullif(sum(st.qty) FILTER (WHERE st.unit_price > 0), 0) AS avg_price,
                           sum(st.qty * st.unit_price) FILTER (WHERE st.unit_cost IS NOT NULL AND st.unit_price > 0)
                               AS costed_revenue,
                           sum(st.qty * st.unit_cost) FILTER (WHERE st.unit_cost IS NOT NULL AND st.unit_price > 0)
                               AS cogs,
                           percentile_cont(0.1) WITHIN GROUP (ORDER BY (st.unit_price - st.unit_cost) / st.unit_price)
                               FILTER (WHERE st.unit_cost IS NOT NULL AND st.unit_price > 0) AS low_margin
                      FROM sales_transactions st
                     WHERE st.tenant_id = :t AND st.txn_date BETWEEN :from AND :to
                     GROUP BY st.product_id
                ), c AS (
                    SELECT DISTINCT ON (pp.product_id) pp.product_id, pp.cost
                      FROM product_prices pp
                     WHERE pp.tenant_id = :t AND pp.cost IS NOT NULL AND pp.cost > 0
                       AND pp.effective_from <= :to
                     ORDER BY pp.product_id, (pp.store_id IS NULL) DESC, pp.effective_from DESC, pp.created_at DESC
                )
                SELECT s.product_id, p.category, s.lines, s.avg_price, s.costed_revenue, s.cogs, s.low_margin,
                       c.cost AS list_cost
                  FROM s
                  JOIN products p ON p.id = s.product_id AND p.tenant_id = :t
                  LEFT JOIN c ON c.product_id = s.product_id
                """, Sql.params(w), (rs, i) -> {
                    BigDecimal avgPrice = Sql.money(rs, "avg_price");
                    BigDecimal costedRevenue = rs.getBigDecimal("costed_revenue");
                    BigDecimal cogs = rs.getBigDecimal("cogs");
                    BigDecimal listCost = rs.getBigDecimal("list_cost");
                    BigDecimal margin = costedRevenue != null && costedRevenue.signum() > 0 && cogs != null
                            ? PricingMath.pct(costedRevenue.subtract(cogs), costedRevenue)
                            : PricingMath.marginPct(avgPrice, listCost);
                    BigDecimal low = rs.getBigDecimal("low_margin");
                    return new ItemMargin(rs.getObject("product_id", UUID.class), rs.getString("category"),
                            rs.getLong("lines"), avgPrice, margin,
                            low == null ? null : low.multiply(PricingMath.HUNDRED).setScale(4, PricingMath.ROUNDING));
                }));
    }
}

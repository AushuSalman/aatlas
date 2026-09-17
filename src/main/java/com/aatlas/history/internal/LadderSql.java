package com.aatlas.history.internal;

import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The bulk rungs of the truth ladder: one statement per rung over the whole tenant, so the
 * bulk model never runs a query per pair. The single-pair ladder goes through the public
 * history interfaces instead; both apply the same rules in {@link PriceLadderImpl}.
 */
@Repository
class LadderSql {

    /** One (product, store) group of purchase lines in the window: the sums a weighted landed cost needs. */
    record PoGroup(UUID productId, UUID storeId, BigDecimal landedTimesQty, BigDecimal qty, LocalDate lastOrder) {
    }

    /** One supplier's quote for a product, with the country that decides its lane. */
    record Quote(UUID productId, BigDecimal exWorks, LocalDate asOf, String country) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    LadderSql(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** T1 per product across every branch, for the item-level price and cost rungs. */
    Map<UUID, SalesStats> itemStats(Window w) {
        Map<UUID, SalesStats> out = new HashMap<>();
        jdbc.query("SELECT st.product_id, " + SalesHistoryJdbc.T1_AGG + " FROM sales_transactions st"
                + " WHERE st.tenant_id = :t AND st.txn_date BETWEEN :from AND :to GROUP BY st.product_id",
                Sql.params(w), rs -> {
                    out.put(Sql.uuid(rs, "product_id"), SalesHistoryJdbc.readStats(rs));
                });
        return out;
    }

    /** Purchase lines in the window grouped by (product, branch); a null branch is its own group. */
    List<PoGroup> purchaseGroups(Window w) {
        return jdbc.query("""
                SELECT po.product_id, po.store_id, sum(po.landed * po.qty) AS lq, sum(po.qty) AS q,
                       max(po.order_date) AS last_order
                  FROM purchase_order po
                 WHERE po.tenant_id = :t AND po.product_id IS NOT NULL AND po.order_date BETWEEN :from AND :to
                 GROUP BY po.product_id, po.store_id
                """, Sql.params(w), (rs, i) -> new PoGroup(Sql.uuid(rs, "product_id"), Sql.uuid(rs, "store_id"),
                Sql.moneyOrZero(rs, "lq"), Sql.moneyOrZero(rs, "q"), Sql.date(rs, "last_order")));
    }

    /** Every ex-works quote on file, with the supplier's country. */
    List<Quote> quotes() {
        return jdbc.query("""
                SELECT sp.product_id, sp.ex_works, sp.ex_works_as_of, s.country
                  FROM supplier_products sp JOIN suppliers s ON s.id = sp.supplier_id
                 WHERE sp.tenant_id = :t AND sp.ex_works IS NOT NULL AND sp.ex_works > 0
                """, Sql.params(), (rs, i) -> new Quote(Sql.uuid(rs, "product_id"), Sql.money(rs, "ex_works"),
                Sql.date(rs, "ex_works_as_of"), rs.getString("country")));
    }
}

package com.aatlas.demandmodel.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's sales as weekly points per item and branch, and its competitor observations:
 * what {@link WeeklyGrid} turns into training rows.
 *
 * <p>Reads {@code sales_transactions} directly (uploaded and recorded sales alike), by the item
 * number and branch code on the line, falling back to the linked product and store for lines
 * that carry only ids. Only the busiest {@code maxPairs} pairs by units are loaded, which keeps
 * a very large catalogue inside the instance's memory; the rest fall back to the rule engine.
 */
@Repository
class TrainingRows {

    record Loaded(List<WeeklyGrid.SalesWeek> sales, List<WeeklyGrid.CompetitorObs> competitors) {
    }

    private final JdbcTemplate jdbc;

    TrainingRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Loaded load(UUID tenantId, LocalDate lastWeek, int weeksBack, int maxPairs) {
        LocalDate from = lastWeek.minusWeeks(weeksBack);
        LocalDate to = lastWeek.plusDays(6);
        Map<String, String> categories = new HashMap<>();
        jdbc.query("select item_number, category from products where tenant_id = ?", rs -> {
            categories.put(rs.getString(1), rs.getString(2));
        }, tenantId);

        List<WeeklyGrid.SalesWeek> sales = jdbc.query("""
                with w as (
                    select coalesce(t.item_number, p.item_number) as item,
                           coalesce(t.branch_code, s.store_code) as store,
                           date_trunc('week', t.txn_date)::date as wk,
                           sum(t.qty) as units,
                           sum(t.qty * t.unit_price) as revenue,
                           sum(t.qty * t.unit_cost) filter (where t.unit_cost is not null and t.unit_cost > 0) as cost_total,
                           sum(t.qty) filter (where t.unit_cost is not null and t.unit_cost > 0) as costed_units
                      from sales_transactions t
                      left join products p on p.id = t.product_id
                      left join stores s on s.id = t.store_id
                     where t.tenant_id = ? and t.txn_date >= ? and t.txn_date <= ?
                       and t.qty > 0 and t.unit_price > 0
                     group by 1, 2, 3),
                top as (
                    select item, store from w where item is not null and store is not null
                     group by item, store order by sum(units) desc limit ?)
                select w.item, w.store, w.wk, w.units, w.revenue, w.cost_total, w.costed_units
                  from w join top on top.item = w.item and top.store = w.store
                 order by w.item, w.store, w.wk
                """, (rs, i) -> {
                    String item = rs.getString(1);
                    double units = dbl(rs.getBigDecimal(4));
                    double revenue = dbl(rs.getBigDecimal(5));
                    double costTotal = dbl(rs.getBigDecimal(6));
                    double costedUnits = dbl(rs.getBigDecimal(7));
                    return new WeeklyGrid.SalesWeek(item, rs.getString(2), categories.get(item),
                            rs.getObject(3, LocalDate.class), units, units > 0 ? revenue / units : 0,
                            costedUnits > 0 ? costTotal / costedUnits : 0);
                }, tenantId, Date.valueOf(from), Date.valueOf(to), maxPairs);

        List<WeeklyGrid.CompetitorObs> comps = jdbc.query("""
                select p.item_number, c.observed_at::date, c.price
                  from competitor_prices c
                  join products p on p.id = c.product_id
                 where c.tenant_id = ? and c.price > 0 and c.observed_at >= ?
                 order by 1, 2
                """, (rs, i) -> new WeeklyGrid.CompetitorObs(rs.getString(1), rs.getObject(2, LocalDate.class),
                        dbl(rs.getBigDecimal(3))),
                tenantId, Date.valueOf(from));
        return new Loaded(new ArrayList<>(sales), new ArrayList<>(comps));
    }

    /**
     * Sales the model has not learned from yet: recorded since the last run, dated in the week still open
     * (counted from {@code countedFrom}, the Monday after it closes), and in finished weeks after the one
     * the model trained through (the next run picks those up).
     */
    record Pending(int sinceTrained, int inOpenWeek, LocalDate countedFrom, int awaitingRetrain) {
    }

    @Transactional(readOnly = true)
    Pending pending(UUID tenantId, Instant trainedAt, LocalDate toWeek, LocalDate today) {
        LocalDate openWeek = WeeklyGrid.lastCompleteWeek(today).plusWeeks(1);
        LocalDate countedFrom = openWeek.plusWeeks(1);
        if (trainedAt == null) {
            return new Pending(0, 0, countedFrom, 0);
        }
        LocalDate learnedThrough = toWeek != null ? toWeek.plusDays(6) : openWeek.minusDays(1);
        return jdbc.query("""
                select count(*) filter (where created_at > ?),
                       count(*) filter (where txn_date >= ?),
                       count(*) filter (where txn_date > ? and txn_date < ?)
                  from sales_transactions
                 where tenant_id = ? and qty > 0 and (txn_date > ? or created_at > ?)
                """, rs -> rs.next() ? new Pending(rs.getInt(1), rs.getInt(2), countedFrom, rs.getInt(3))
                        : new Pending(0, 0, countedFrom, 0),
                Timestamp.from(trainedAt), Date.valueOf(openWeek), Date.valueOf(learnedThrough), Date.valueOf(openWeek),
                tenantId, Date.valueOf(learnedThrough), Timestamp.from(trainedAt));
    }

    private static double dbl(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}

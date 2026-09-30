package com.aatlas.decisions.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.decisions.DecisionOutcomes;
import com.aatlas.history.SalesHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code decision_outcomes}: written by {@link OutcomeMeasurer}, read by the sell chain and Decision history. */
@Repository
class OutcomesJdbc implements DecisionOutcomes {

    /** Each measured outcome weighs as much as this many months of sales history in the blend. */
    static final int MONTHS_PER_OUTCOME = 3;

    private final JdbcTemplate jdbc;

    OutcomesJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A sell decision still to be measured. */
    record Pending(String dealKey, String itemNumber, String storeCode, LocalDate date, BigDecimal cost,
            BigDecimal baseline, BigDecimal applied) {
    }

    /**
     * Sell decisions whose after-window has closed and that have no final outcome: never measured,
     * or measured as insufficient while still recent enough that more sales may yet arrive.
     */
    @Transactional(readOnly = true)
    List<Pending> pending(UUID tenantId, LocalDate today, int windowDays, int retryDays) {
        return jdbc.query("""
                select d.deal_key, d.item_number, d.destination_id, d.deal_date, d.cost, d.baseline_price, d.actual_price
                  from deal d
                  left join decision_outcomes o on o.tenant_id = d.tenant_id and o.deal_key = d.deal_key
                 where d.tenant_id = ? and d.side = 'sell' and d.recorded
                   and d.deal_date <= ? and d.deal_date >= ?
                   and (o.deal_key is null or (o.status = 'insufficient' and d.deal_date >= ?))
                 order by d.deal_date
                 limit 2000
                """, (rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)),
                tenantId, today.minusDays(windowDays), today.minusDays(LEARN_DAYS), today.minusDays(retryDays));
    }

    @Transactional
    void save(UUID tenantId, Pending p, int windowDays, OutcomeMath.Result r) {
        jdbc.update("""
                insert into decision_outcomes (tenant_id, deal_key, side, item_number, store_code, decision_date,
                    window_days, status, verdict, units_before_week, units_after_week, price_before, price_after,
                    profit_before_week, profit_after_week, price_change_pct, volume_change_pct, profit_change_pct,
                    implied_elasticity, note, measured_at)
                values (?, ?, 'sell', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, deal_key) do update set
                    window_days = excluded.window_days, status = excluded.status, verdict = excluded.verdict,
                    units_before_week = excluded.units_before_week, units_after_week = excluded.units_after_week,
                    price_before = excluded.price_before, price_after = excluded.price_after,
                    profit_before_week = excluded.profit_before_week, profit_after_week = excluded.profit_after_week,
                    price_change_pct = excluded.price_change_pct, volume_change_pct = excluded.volume_change_pct,
                    profit_change_pct = excluded.profit_change_pct, implied_elasticity = excluded.implied_elasticity,
                    note = excluded.note, measured_at = now()
                """, tenantId, p.dealKey(), p.itemNumber(), p.storeCode(), p.date(), windowDays, r.status(),
                r.verdict(), r.unitsBeforeWeek(), r.unitsAfterWeek(), r.priceBefore(), r.priceAfter(),
                r.profitBeforeWeek(), r.profitAfterWeek(), r.priceChangePct(), r.volumeChangePct(),
                r.profitChangePct(), r.impliedElasticity(), r.label() + ". " + r.note());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Learned> learned(String itemNumber, LocalDate today) {
        return jdbc.query("""
                select percentile_cont(0.5) within group (order by implied_elasticity), count(*)
                  from decision_outcomes
                 where tenant_id = ? and item_number = ? and status = 'measured'
                   and implied_elasticity is not null and decision_date >= ?
                """, (rs, i) -> {
                    long n = rs.getLong(2);
                    BigDecimal median = rs.getBigDecimal(1);
                    return n == 0 || median == null ? null
                            : new Learned(median.setScale(4, RoundingMode.HALF_UP), (int) n);
                }, TenantContext.requireTenantId(), itemNumber, today.minusDays(LEARN_DAYS))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public SalesHistory.Elasticity blend(SalesHistory.Elasticity base, String itemNumber, LocalDate today) {
        Optional<Learned> learned = learned(itemNumber, today);
        if (learned.isEmpty()) {
            return base;
        }
        return blend(base, learned.get());
    }

    /**
     * The measured outcomes' median sensitivity folded into the sales-history estimate. Alone when
     * the history measured nothing (the default prior); otherwise a weighted mean where each outcome
     * counts as {@value #MONTHS_PER_OUTCOME} months of history - an observed response to one of your
     * own price moves is better evidence than a month of passive sales. Clamped to the chain's range.
     */
    static SalesHistory.Elasticity blend(SalesHistory.Elasticity base, Learned l) {
        double learned = Math.max(-3, Math.min(-0.1, l.elasticity().doubleValue()));
        // Standard error shrinks with more outcomes: one is weak evidence, a dozen is strong.
        BigDecimal seOutcomes = BigDecimal.valueOf(0.6 / Math.sqrt(l.n())).setScale(4, RoundingMode.HALF_UP);
        if (base == null || base.coefficient() == null || SalesHistory.Elasticity.DEFAULT.equals(base.basis())) {
            return new SalesHistory.Elasticity(BigDecimal.valueOf(learned).setScale(4, RoundingMode.HALF_UP), null,
                    l.n(), "outcomes", seOutcomes);
        }
        double wBase = Math.max(1, base.n());
        double wOut = (double) l.n() * MONTHS_PER_OUTCOME;
        double coef = (wBase * base.coefficient().doubleValue() + wOut * learned) / (wBase + wOut);
        BigDecimal se = base.stdError() == null ? seOutcomes
                : BigDecimal.valueOf(base.stdError().doubleValue() * Math.sqrt(wBase / (wBase + wOut)))
                        .setScale(4, RoundingMode.HALF_UP);
        return new SalesHistory.Elasticity(BigDecimal.valueOf(coef).setScale(4, RoundingMode.HALF_UP), base.r2(),
                base.n() + l.n(), base.basis() + "+outcomes", se);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Outcome> byDeal() {
        Map<String, Outcome> out = new LinkedHashMap<>();
        jdbc.query("""
                select deal_key, verdict, note, volume_change_pct, profit_change_pct, price_change_pct, measured_at
                  from decision_outcomes where tenant_id = ? order by decision_date desc limit 5000
                """, rs -> {
                    String note = rs.getString(3);
                    String label = note == null ? null : note.contains(". ") ? note.substring(0, note.indexOf(". ")) : note;
                    OffsetDateTime at = rs.getObject(7, OffsetDateTime.class);
                    out.put(rs.getString(1), new Outcome(rs.getString(1), rs.getString(2), label, rs.getBigDecimal(4),
                            rs.getBigDecimal(5), rs.getBigDecimal(6), at == null ? null : at.toLocalDate()));
                }, TenantContext.requireTenantId());
        return out;
    }
}

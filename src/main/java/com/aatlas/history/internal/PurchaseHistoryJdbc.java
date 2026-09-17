package com.aatlas.history.internal;

import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Window;
import com.aatlas.history.PricingMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code purchase_order}, reduced. Items are addressed by {@code item_number} (the import
 * writes the canonical one) and suppliers by {@code supplier_key}; every statement is the
 * T7 aggregate with a different predicate or grouping.
 */
@Repository
class PurchaseHistoryJdbc implements PurchaseHistory {

    static final String T7_AGG = """
            count(*) AS pos,
            count(*) FILTER (WHERE po.received_date IS NOT NULL) AS received,
            coalesce(sum(po.qty), 0) AS units,
            coalesce(sum(po.spend), 0) AS spend,
            coalesce(sum(po.saved), 0) AS saved,
            coalesce(sum(greatest(0, po.landed - po.baseline) * po.qty), 0) AS overpaid,
            coalesce(sum(po.leaked), 0) AS leaked,
            coalesce(sum(po.baseline_spend), 0) AS baseline_spend,
            sum(po.landed * po.qty) / nullif(sum(po.qty), 0) AS avg_landed,
            sum(po.ex_works * po.qty) / nullif(sum(po.qty), 0) AS avg_ex_works,
            (array_agg(po.landed ORDER BY po.order_date DESC, po.seq DESC))[1] AS last_landed,
            (array_agg(po.ex_works ORDER BY po.order_date DESC, po.seq DESC))[1] AS last_ex_works,
            min(po.order_date) AS first_order,
            max(po.order_date) AS last_order,
            avg(po.received_date - po.order_date) FILTER (WHERE po.received_date IS NOT NULL) AS avg_lead,
            stddev_samp(po.received_date - po.order_date) FILTER (WHERE po.received_date IS NOT NULL) AS lead_sd,
            count(*) FILTER (WHERE po.on_time IS NOT NULL) AS measurable,
            100.0 * count(*) FILTER (WHERE po.on_time)
                / nullif(count(*) FILTER (WHERE po.on_time IS NOT NULL), 0) AS otif_pct,
            100.0 * count(*) FILTER (WHERE po.received_date IS NOT NULL AND (po.qty_received IS NULL OR po.qty_received >= po.qty))
                / nullif(count(*) FILTER (WHERE po.received_date IS NOT NULL), 0) AS in_full_pct
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PurchaseHistoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    // ---- predicates ----------------------------------------------------------------------

    private static String where(boolean item, boolean supplier, boolean store, boolean dated) {
        return " WHERE po.tenant_id = :t"
                + (item ? " AND po.item_number = :item" : "")
                + (supplier ? " AND po.supplier_id = :k" : "")
                + (store ? " AND po.store_id = :s" : "")
                + (dated ? " AND po.order_date BETWEEN :from AND :to" : "");
    }

    private static MapSqlParameterSource params(Window wOrNull) {
        return wOrNull == null ? Sql.params() : Sql.params(wOrNull);
    }

    static PoStats readStats(ResultSet rs) throws SQLException {
        long pos = rs.getLong("pos");
        if (pos == 0) {
            return PoStats.empty();
        }
        BigDecimal otif = rs.getBigDecimal("otif_pct");
        BigDecimal inFull = rs.getBigDecimal("in_full_pct");
        BigDecimal avgLead = rs.getBigDecimal("avg_lead");
        BigDecimal leadSd = rs.getBigDecimal("lead_sd");
        return new PoStats(pos, rs.getLong("received"), Sql.moneyOrZero(rs, "units"), Sql.moneyOrZero(rs, "spend"),
                Sql.money(rs, "avg_landed"), Sql.money(rs, "avg_ex_works"), Sql.money(rs, "last_landed"),
                Sql.money(rs, "last_ex_works"), Sql.date(rs, "first_order"), Sql.date(rs, "last_order"),
                avgLead == null ? null : avgLead.setScale(1, RoundingMode.HALF_UP),
                leadSd == null ? null : leadSd.setScale(1, RoundingMode.HALF_UP),
                rs.getLong("measurable"),
                otif == null ? null : otif.setScale(1, RoundingMode.HALF_UP),
                inFull == null ? null : inFull.setScale(1, RoundingMode.HALF_UP),
                Sql.moneyOrZero(rs, "saved"), Sql.moneyOrZero(rs, "overpaid"), Sql.moneyOrZero(rs, "leaked"),
                Sql.moneyOrZero(rs, "baseline_spend"));
    }

    private PoStats stats(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + T7_AGG + " FROM purchase_order po" + where, params,
                rs -> rs.next() ? readStats(rs) : PoStats.empty());
    }

    // ---- coverage ------------------------------------------------------------------------

    @Override
    public boolean hasHistory() {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM purchase_order po WHERE po.tenant_id = :t)",
                Sql.params(), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    @Override
    public PurchaseCoverage coverage() {
        return jdbc.query("""
                SELECT count(*) AS rows_, count(DISTINCT date_trunc('month', po.order_date)) AS months,
                       min(po.order_date) AS earliest, max(po.order_date) AS latest,
                       count(DISTINCT po.item_number) AS items, count(DISTINCT po.supplier_id) AS suppliers,
                       count(DISTINCT po.branch_id) AS branches,
                       count(*) FILTER (WHERE po.received_date IS NOT NULL) AS received
                  FROM purchase_order po
                 WHERE po.tenant_id = :t
                """, Sql.params(), rs -> {
            if (!rs.next() || rs.getLong("rows_") == 0) {
                return PurchaseCoverage.none();
            }
            return new PurchaseCoverage(rs.getLong("rows_"), rs.getInt("months"), Sql.date(rs, "earliest"),
                    Sql.date(rs, "latest"), rs.getInt("items"), rs.getInt("suppliers"), rs.getInt("branches"),
                    rs.getLong("received"));
        });
    }

    // ---- items ---------------------------------------------------------------------------

    @Override
    public PoStats itemSupplier(String itemNumber, String supplierKey, Window w) {
        return stats(where(true, true, false, true), Sql.params(w).addValue("item", itemNumber).addValue("k", supplierKey));
    }

    @Override
    public PoStats itemAtStore(String itemNumber, UUID storeId, Window w) {
        return stats(where(true, false, true, true), Sql.params(w).addValue("item", itemNumber).addValue("s", storeId));
    }

    @Override
    public ItemPurchases item(String itemNumber, Window w) {
        PoStats all = stats(where(true, false, false, true), Sql.params(w).addValue("item", itemNumber));
        return new ItemPurchases(itemNumber, all, itemSuppliers(itemNumber, w, all.spend()));
    }

    /** T7 grouped by supplier for one item, spend descending, with each one's share. */
    private List<SupplierShare> itemSuppliers(String itemNumber, Window wOrNull, BigDecimal totalSpend) {
        String sql = "SELECT po.supplier_id, max(po.supplier_name) AS supplier_name, max(po.country) AS country, "
                + T7_AGG + " FROM purchase_order po" + where(true, false, false, wOrNull != null)
                + " GROUP BY po.supplier_id ORDER BY spend DESC, po.supplier_id";
        return jdbc.query(sql, params(wOrNull).addValue("item", itemNumber), (rs, i) -> {
            PoStats stats = readStats(rs);
            BigDecimal share = PricingMath.pct(stats.spend(), totalSpend);
            return new SupplierShare(rs.getString("supplier_id"), rs.getString("supplier_name"),
                    rs.getString("country"), share == null ? null : share.setScale(1, RoundingMode.HALF_UP), stats);
        });
    }

    @Override
    public Optional<SupplierShare> incumbent(String itemNumber, LocalDate today) {
        ItemPurchases w12 = item(itemNumber, Window.trailingMonths(today, 12));
        if (!w12.suppliers().isEmpty()) {
            return Optional.of(w12.suppliers().get(0));
        }
        String lastSupplier = jdbc.query("""
                SELECT po.supplier_id FROM purchase_order po
                 WHERE po.tenant_id = :t AND po.item_number = :item
                 ORDER BY po.order_date DESC, po.seq DESC LIMIT 1
                """, Sql.params().addValue("item", itemNumber), rs -> rs.next() ? rs.getString("supplier_id") : null);
        if (lastSupplier == null) {
            return Optional.empty();
        }
        PoStats allTime = stats(where(true, false, false, false), Sql.params().addValue("item", itemNumber));
        return itemSuppliers(itemNumber, null, allTime.spend()).stream()
                .filter(s -> s.supplierKey().equals(lastSupplier))
                .findFirst();
    }

    // ---- suppliers -----------------------------------------------------------------------

    @Override
    public SupplierPurchases supplier(String supplierKey, LocalDate today) {
        Window w12 = Window.trailingMonths(today, 12);
        PoStats inWindow = stats(where(false, true, false, true), Sql.params(w12).addValue("k", supplierKey));
        PoStats allTime = stats(where(false, true, false, false), Sql.params().addValue("k", supplierKey));
        PoStats tenant = stats(where(false, false, false, true), Sql.params(w12));
        BigDecimal share = PricingMath.pct(inWindow.spend(), tenant.spend());
        return new SupplierPurchases(supplierKey, inWindow, allTime,
                share == null ? null : share.setScale(1, RoundingMode.HALF_UP),
                otifTrend(today).getOrDefault(supplierKey, emptyTrend(today)));
    }

    @Override
    public List<SupplierPurchases> bySupplier(Window w) {
        Map<String, PoStats> inWindow = groupedBySupplier(w);
        Map<String, PoStats> allTime = groupedBySupplier(null);
        BigDecimal total = inWindow.values().stream().map(PoStats::spend).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, List<MonthOtif>> trends = otifTrend(w.to());
        Set<String> keys = new LinkedHashSet<>(inWindow.keySet());
        keys.addAll(allTime.keySet());
        List<SupplierPurchases> out = new ArrayList<>();
        for (String key : keys) {
            PoStats now = inWindow.getOrDefault(key, PoStats.empty());
            BigDecimal share = PricingMath.pct(now.spend(), total);
            out.add(new SupplierPurchases(key, now, allTime.getOrDefault(key, PoStats.empty()),
                    share == null ? null : share.setScale(1, RoundingMode.HALF_UP),
                    trends.getOrDefault(key, emptyTrend(w.to()))));
        }
        out.sort(Comparator.comparing((SupplierPurchases s) -> s.w12().spend()).reversed()
                .thenComparing(SupplierPurchases::supplierKey));
        return out;
    }

    private Map<String, PoStats> groupedBySupplier(Window wOrNull) {
        Map<String, PoStats> out = new LinkedHashMap<>();
        jdbc.query("SELECT po.supplier_id, " + T7_AGG + " FROM purchase_order po"
                + where(false, false, false, wOrNull != null) + " GROUP BY po.supplier_id",
                params(wOrNull), rs -> {
                    out.put(rs.getString("supplier_id"), readStats(rs));
                });
        return out;
    }

    /** OTIF per supplier per month over the last six months ending today's month; missing months null. */
    private Map<String, List<MonthOtif>> otifTrend(LocalDate today) {
        Window w = new Window(today, today);
        LocalDate m0 = w.m0(6);
        Map<String, Map<LocalDate, MonthOtif>> raw = new HashMap<>();
        jdbc.query("""
                SELECT po.supplier_id, date_trunc('month', po.received_date)::date AS month,
                       count(*) AS received,
                       100.0 * count(*) FILTER (WHERE po.on_time)
                           / nullif(count(*) FILTER (WHERE po.on_time IS NOT NULL), 0) AS otif_pct
                  FROM purchase_order po
                 WHERE po.tenant_id = :t AND po.received_date BETWEEN :m0 AND :to
                 GROUP BY 1, 2
                """, Sql.params(w).addValue("m0", m0), rs -> {
            BigDecimal otif = rs.getBigDecimal("otif_pct");
            LocalDate month = Sql.date(rs, "month");
            raw.computeIfAbsent(rs.getString("supplier_id"), k -> new HashMap<>())
                    .put(month, new MonthOtif(month, otif == null ? null : otif.setScale(1, RoundingMode.HALF_UP),
                            rs.getLong("received")));
        });
        Map<String, List<MonthOtif>> out = new HashMap<>();
        raw.forEach((key, months) -> {
            List<MonthOtif> trend = new ArrayList<>();
            for (LocalDate month = m0; !month.isAfter(w.m1()); month = month.plusMonths(1)) {
                trend.add(months.getOrDefault(month, new MonthOtif(month, null, 0)));
            }
            out.put(key, trend);
        });
        return out;
    }

    private static List<MonthOtif> emptyTrend(LocalDate today) {
        Window w = new Window(today, today);
        List<MonthOtif> trend = new ArrayList<>();
        for (LocalDate month = w.m0(6); !month.isAfter(w.m1()); month = month.plusMonths(1)) {
            trend.add(new MonthOtif(month, null, 0));
        }
        return trend;
    }

    // ---- groups --------------------------------------------------------------------------

    @Override
    public List<PoGroup> byCategory(Window w) {
        return groups(w, "coalesce(po.category, 'uncategorised')", "coalesce(po.category, 'uncategorised')");
    }

    @Override
    public List<PoGroup> byBranch(Window w) {
        return groups(w, "coalesce(po.branch_id, 'no-branch')", "coalesce(po.branch_name, 'No branch on file')");
    }

    @Override
    public List<PoGroup> byOrigin(Window w) {
        return groups(w, "po.country", "po.country");
    }

    private record Grouped(String label, PoStats stats) {
    }

    private List<PoGroup> groups(Window w, String keyExpr, String labelExpr) {
        String sql = "SELECT " + keyExpr + " AS grp_key, max(" + labelExpr + ") AS grp_label, " + T7_AGG
                + " FROM purchase_order po" + where(false, false, false, true) + " GROUP BY " + keyExpr;
        Map<String, Grouped> current = grouped(sql, w);
        Map<String, Grouped> prior = grouped(sql, w.prior());
        Set<String> keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(prior.keySet());
        List<PoGroup> out = new ArrayList<>();
        for (String key : keys) {
            Grouped now = current.get(key);
            Grouped before = prior.get(key);
            out.add(new PoGroup(key, now != null ? now.label() : before.label(),
                    now != null ? now.stats() : PoStats.empty(), before != null ? before.stats() : PoStats.empty()));
        }
        out.sort(Comparator.comparing((PoGroup g) -> g.current().spend()).reversed().thenComparing(PoGroup::key));
        return out;
    }

    private Map<String, Grouped> grouped(String sql, Window w) {
        Map<String, Grouped> out = new LinkedHashMap<>();
        jdbc.query(sql, Sql.params(w), rs -> {
            out.put(rs.getString("grp_key"), new Grouped(rs.getString("grp_label"), readStats(rs)));
        });
        return out;
    }

    // ---- monthly, item stats, totals -----------------------------------------------------

    @Override
    public List<PoMonth> monthly(String itemNumberOrNull, String supplierKeyOrNull, int months, LocalDate today) {
        Window w = new Window(today, today);
        MapSqlParameterSource params = Sql.params(w).addValue("m0", w.m0(months)).addValue("m1", w.m1());
        String predicate = "";
        if (itemNumberOrNull != null) {
            predicate += " AND po.item_number = :item";
            params.addValue("item", itemNumberOrNull);
        }
        if (supplierKeyOrNull != null) {
            predicate += " AND po.supplier_id = :k";
            params.addValue("k", supplierKeyOrNull);
        }
        return jdbc.query("""
                WITH m AS (SELECT generate_series(CAST(:m0 AS date), CAST(:m1 AS date), interval '1 month')::date AS month),
                a AS (SELECT date_trunc('month', po.order_date)::date AS month, sum(po.spend) AS spend,
                             sum(po.qty) AS units, count(*) AS pos,
                             sum(po.landed * po.qty) / nullif(sum(po.qty), 0) AS avg_landed,
                             sum(po.saved) AS saved, sum(po.leaked) AS leaked
                        FROM purchase_order po
                       WHERE po.tenant_id = :t %s AND po.order_date BETWEEN :m0 AND :to
                       GROUP BY 1)
                SELECT m.month, coalesce(a.spend, 0) AS spend, coalesce(a.units, 0) AS units, coalesce(a.pos, 0) AS pos,
                       a.avg_landed, coalesce(a.saved, 0) AS saved, coalesce(a.leaked, 0) AS leaked
                  FROM m LEFT JOIN a USING (month)
                 ORDER BY m.month
                """.formatted(predicate), params, (rs, i) -> new PoMonth(Sql.date(rs, "month"),
                Sql.moneyOrZero(rs, "spend"), Sql.moneyOrZero(rs, "units"), rs.getLong("pos"),
                Sql.money(rs, "avg_landed"), Sql.moneyOrZero(rs, "saved"), Sql.moneyOrZero(rs, "leaked")));
    }

    @Override
    public Map<String, PoStats> itemStats(Window w) {
        Map<String, PoStats> out = new HashMap<>();
        jdbc.query("SELECT po.item_number, " + T7_AGG + " FROM purchase_order po" + where(false, false, false, true)
                + " GROUP BY po.item_number", Sql.params(w), rs -> {
            out.put(rs.getString("item_number"), readStats(rs));
        });
        return out;
    }

    @Override
    public BigDecimal savedTotal(Window w) {
        BigDecimal total = jdbc.queryForObject("SELECT coalesce(sum(po.saved), 0) FROM purchase_order po"
                + where(false, false, false, true), Sql.params(w), BigDecimal.class);
        return Sql.scaled(total);
    }

    @Override
    public BigDecimal overpaidTotal(Window w) {
        BigDecimal total = jdbc.queryForObject(
                "SELECT coalesce(sum(greatest(0, po.landed - po.baseline) * po.qty), 0) FROM purchase_order po"
                        + where(false, false, false, true), Sql.params(w), BigDecimal.class);
        return Sql.scaled(total);
    }
}

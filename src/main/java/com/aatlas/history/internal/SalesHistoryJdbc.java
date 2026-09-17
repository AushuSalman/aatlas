package com.aatlas.history.internal;

import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import com.aatlas.history.PricingMath;
import com.aatlas.history.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * {@code sales_transactions}, reduced. Every statement binds the tenant explicitly and the
 * window's precomputed dates; the item/branch statements hit
 * {@code sales_transactions_line_idx} exactly, the tenant-wide ones its tenant prefix plus
 * partition pruning.
 */
@Repository
class SalesHistoryJdbc implements SalesHistory {

    /** The T1 aggregate block, shared by every stats, group and pair query. */
    static final String T1_AGG = """
            count(*) AS txns,
            coalesce(sum(st.qty), 0) AS units,
            coalesce(sum(st.qty * st.unit_price), 0) AS revenue,
            sum(st.qty * st.unit_cost) FILTER (WHERE st.unit_cost IS NOT NULL) AS cogs,
            coalesce(sum(st.qty) FILTER (WHERE st.unit_cost IS NOT NULL), 0) AS costed_units,
            coalesce(sum(st.qty * st.unit_price) FILTER (WHERE st.unit_cost IS NOT NULL), 0) AS costed_revenue,
            count(DISTINCT coalesce(st.customer_id::text, lower(st.customer_code))) AS customers,
            min(st.unit_price) FILTER (WHERE st.unit_price > 0) AS min_price,
            max(st.unit_price) FILTER (WHERE st.unit_price > 0) AS max_price,
            sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0)
                / nullif(sum(st.qty) FILTER (WHERE st.unit_price > 0), 0) AS avg_price,
            sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0 AND st.txn_date >= :r0)
                / nullif(sum(st.qty) FILTER (WHERE st.unit_price > 0 AND st.txn_date >= :r0), 0) AS last_price_90,
            sum(st.qty * st.unit_cost) FILTER (WHERE st.unit_cost IS NOT NULL AND st.txn_date >= :r0)
                / nullif(sum(st.qty) FILTER (WHERE st.unit_cost IS NOT NULL AND st.txn_date >= :r0), 0) AS last_cost_90,
            count(*) FILTER (WHERE st.unit_cost > st.unit_price) AS below_cost,
            count(*) FILTER (WHERE st.unit_price = 0) AS zero_price,
            min(st.txn_date) AS first_sale,
            max(st.txn_date) AS last_sale
            """;

    private static final String PERCENTILES = """
            round(CAST(percentile_cont(0.25) WITHIN GROUP (ORDER BY st.unit_price) AS numeric), 4) AS q1,
            round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY st.unit_price) AS numeric), 4) AS median,
            round(CAST(percentile_cont(0.75) WITHIN GROUP (ORDER BY st.unit_price) AS numeric), 4) AS q3
            """;

    private static final int MIN_BAND_ROWS = 3;
    private static final int MIN_STORE_MEDIAN_ROWS = 3;

    private final NamedParameterJdbcTemplate jdbc;

    SalesHistoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    // ---- coverage ------------------------------------------------------------------------

    @Override
    public boolean hasHistory() {
        Boolean any = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sales_transactions st WHERE st.tenant_id = :t)",
                Sql.params(), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    @Override
    public Coverage coverage() {
        return jdbc.query("""
                SELECT count(*) AS rows_, count(DISTINCT date_trunc('month', st.txn_date)) AS months,
                       min(st.txn_date) AS earliest, max(st.txn_date) AS latest,
                       count(DISTINCT st.product_id) AS items, count(DISTINCT st.store_id) AS stores,
                       count(DISTINCT coalesce(st.customer_id::text, lower(st.customer_code))) AS customers,
                       100.0 * coalesce(sum(st.qty * st.unit_price) FILTER (WHERE st.store_id IS NULL), 0)
                           / nullif(sum(st.qty * st.unit_price), 0) AS no_branch_pct
                  FROM sales_transactions st
                 WHERE st.tenant_id = :t
                """, Sql.params(), rs -> {
            if (!rs.next() || rs.getLong("rows_") == 0) {
                return Coverage.none();
            }
            BigDecimal noBranch = rs.getBigDecimal("no_branch_pct");
            return new Coverage(rs.getLong("rows_"), rs.getInt("months"), Sql.date(rs, "earliest"),
                    Sql.date(rs, "latest"), rs.getInt("items"), rs.getInt("stores"), rs.getInt("customers"),
                    noBranch == null ? BigDecimal.ZERO : noBranch.setScale(1, RoundingMode.HALF_UP));
        });
    }

    // ---- T1 stats ------------------------------------------------------------------------

    @Override
    public SalesStats itemStore(UUID productId, UUID storeId, Window w) {
        return stats(statsSql(true, true), Sql.params(w).addValue("p", productId).addValue("s", storeId));
    }

    @Override
    public SalesStats item(UUID productId, Window w) {
        return stats(statsSql(true, false), Sql.params(w).addValue("p", productId));
    }

    @Override
    public SalesStats store(UUID storeId, Window w) {
        return stats(statsSql(false, true), Sql.params(w).addValue("s", storeId));
    }

    @Override
    public SalesStats tenant(Window w) {
        return stats(statsSql(false, false), Sql.params(w));
    }

    private static String statsSql(boolean product, boolean store) {
        return "SELECT " + T1_AGG + " FROM sales_transactions st WHERE st.tenant_id = :t"
                + (product ? " AND st.product_id = :p" : "")
                + (store ? " AND st.store_id = :s" : "")
                + " AND st.txn_date BETWEEN :from AND :to";
    }

    private SalesStats stats(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, rs -> rs.next() ? readStats(rs) : SalesStats.empty());
    }

    /** Reads the T1 block from the current row; the last-price/cost fallbacks are done here. */
    static SalesStats readStats(ResultSet rs) throws SQLException {
        long txns = rs.getLong("txns");
        if (txns == 0) {
            return SalesStats.empty();
        }
        BigDecimal cogs = Sql.money(rs, "cogs");
        BigDecimal costedUnits = Sql.moneyOrZero(rs, "costed_units");
        BigDecimal costedRevenue = Sql.moneyOrZero(rs, "costed_revenue");
        BigDecimal avgPrice = Sql.money(rs, "avg_price");
        BigDecimal lastPrice90 = Sql.money(rs, "last_price_90");
        BigDecimal lastPrice = lastPrice90 != null ? lastPrice90 : avgPrice;
        String lastPriceSource = lastPrice90 != null ? "sales-90d" : avgPrice != null ? "sales-12m" : null;
        BigDecimal avgCost = Sql.scaled(PricingMath.div(cogs, costedUnits));
        BigDecimal lastCost90 = Sql.money(rs, "last_cost_90");
        BigDecimal lastCost = lastCost90 != null ? lastCost90 : avgCost;
        String lastCostSource = lastCost90 != null ? "sales-90d" : avgCost != null ? "sales-12m" : null;
        BigDecimal marginPct = cogs == null ? null : PricingMath.pct(costedRevenue.subtract(cogs), costedRevenue);
        return new SalesStats(txns, Sql.moneyOrZero(rs, "units"), Sql.moneyOrZero(rs, "revenue"), cogs, costedUnits,
                costedRevenue, rs.getInt("customers"), avgPrice, lastPrice, lastPriceSource,
                Sql.money(rs, "min_price"), Sql.money(rs, "max_price"), avgCost, lastCost, lastCostSource,
                marginPct, rs.getLong("below_cost"), rs.getLong("zero_price"), Sql.date(rs, "first_sale"),
                Sql.date(rs, "last_sale"));
    }

    // ---- groups --------------------------------------------------------------------------

    @Override
    public List<GroupStats> byCategory(Window w) {
        return groups(w, "JOIN products p ON p.id = st.product_id", "p.category", "p.category", "p.category");
    }

    @Override
    public List<GroupStats> byStore(Window w) {
        return groups(w, "LEFT JOIN stores s ON s.id = st.store_id",
                "coalesce(s.store_code, 'no-branch')",
                "CASE WHEN s.id IS NULL THEN 'No branch on file' ELSE btrim(split_part(coalesce(nullif(s.msa_name, ''),"
                        + " s.legal_name), '-', 1)) || ' #' || s.store_code END",
                "s.id, s.store_code, s.msa_name, s.legal_name");
    }

    @Override
    public List<GroupStats> byRegion(Window w) {
        return groups(w, "LEFT JOIN stores s ON s.id = st.store_id "
                        + "LEFT JOIN regions r ON r.country_code = s.country AND r.region_key = s.region_key",
                "coalesce(s.region_key, 'unassigned')", "coalesce(r.label, 'Unassigned')",
                "coalesce(s.region_key, 'unassigned'), coalesce(r.label, 'Unassigned')");
    }

    @Override
    public List<GroupStats> byCustomerSegment(Window w) {
        return groups(w, "LEFT JOIN customers c ON c.id = st.customer_id",
                "coalesce(c.segment, 'unassigned')", "initcap(coalesce(c.segment, 'unassigned'))",
                "coalesce(c.segment, 'unassigned')");
    }

    private record Grouped(String label, SalesStats stats) {
    }

    private List<GroupStats> groups(Window w, String join, String keyExpr, String labelExpr, String groupBy) {
        String sql = "SELECT " + keyExpr + " AS grp_key, " + labelExpr + " AS grp_label, " + T1_AGG
                + " FROM sales_transactions st " + join
                + " WHERE st.tenant_id = :t AND st.txn_date BETWEEN :from AND :to GROUP BY " + groupBy;
        Map<String, Grouped> current = grouped(sql, w);
        Map<String, Grouped> prior = grouped(sql, w.prior());
        Set<String> keys = new LinkedHashSet<>(current.keySet());
        keys.addAll(prior.keySet());
        List<GroupStats> out = new ArrayList<>();
        for (String key : keys) {
            Grouped now = current.get(key);
            Grouped before = prior.get(key);
            String label = now != null ? now.label() : before.label();
            out.add(new GroupStats(key, label, now != null ? now.stats() : SalesStats.empty(),
                    before != null ? before.stats() : SalesStats.empty()));
        }
        out.sort(Comparator.comparing((GroupStats g) -> g.current().revenue()).reversed()
                .thenComparing(GroupStats::key));
        return out;
    }

    private Map<String, Grouped> grouped(String sql, Window w) {
        Map<String, Grouped> out = new LinkedHashMap<>();
        jdbc.query(sql, Sql.params(w), rs -> {
            out.put(rs.getString("grp_key"), new Grouped(rs.getString("grp_label"), readStats(rs)));
        });
        return out;
    }

    // ---- T2 monthly ----------------------------------------------------------------------

    private static final String MONTHLY_TEMPLATE = """
            WITH m AS (SELECT generate_series(CAST(:m0 AS date), CAST(:m1 AS date), interval '1 month')::date AS month),
            a AS (SELECT date_trunc('month', st.txn_date)::date AS month, sum(st.qty) AS units,
                         sum(st.qty * st.unit_price) AS revenue, count(*) AS txns,
                         sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0) AS prev,
                         sum(st.qty) FILTER (WHERE st.unit_price > 0) AS pu,
                         sum(st.qty * st.unit_cost) FILTER (WHERE st.unit_cost IS NOT NULL) AS cogs,
                         sum(st.qty) FILTER (WHERE st.unit_cost IS NOT NULL) AS cu
                    FROM sales_transactions st
                   WHERE st.tenant_id = :t AND %s AND st.txn_date BETWEEN :m0 AND :to
                   GROUP BY 1)
            SELECT m.month, coalesce(a.units, 0) AS units, coalesce(a.revenue, 0) AS revenue,
                   coalesce(a.txns, 0) AS txns,
                   CASE WHEN a.pu > 0 THEN a.prev / a.pu END AS avg_price,
                   CASE WHEN a.cu > 0 THEN a.cogs / a.cu END AS avg_cost
              FROM m LEFT JOIN a USING (month)
             ORDER BY m.month
            """;

    @Override
    public List<MonthPoint> monthly(UUID productId, UUID storeIdOrNull, int months, LocalDate today) {
        String predicate = "st.product_id = :p" + (storeIdOrNull != null ? " AND st.store_id = :s" : "");
        MapSqlParameterSource params = monthlyParams(months, today).addValue("p", productId);
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        return jdbc.query(MONTHLY_TEMPLATE.formatted(predicate), params, (rs, i) -> readMonth(rs));
    }

    @Override
    public List<MonthPoint> monthlyCategory(String category, UUID storeIdOrNull, int months, LocalDate today) {
        String predicate = "st.product_id IN (SELECT id FROM products WHERE tenant_id = :t AND category = :c)"
                + (storeIdOrNull != null ? " AND st.store_id = :s" : "");
        MapSqlParameterSource params = monthlyParams(months, today).addValue("c", category);
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        return jdbc.query(MONTHLY_TEMPLATE.formatted(predicate), params, (rs, i) -> readMonth(rs));
    }

    private static MapSqlParameterSource monthlyParams(int months, LocalDate today) {
        Window w = new Window(today, today);
        return Sql.params(w).addValue("m0", w.m0(months)).addValue("m1", w.m1());
    }

    private static MonthPoint readMonth(ResultSet rs) throws SQLException {
        return new MonthPoint(Sql.date(rs, "month"), Sql.moneyOrZero(rs, "units"), Sql.moneyOrZero(rs, "revenue"),
                rs.getLong("txns"), Sql.money(rs, "avg_price"), Sql.money(rs, "avg_cost"));
    }

    /** T2 per product, for every item of a category: the category elasticity rung's input. */
    Map<UUID, List<MonthPoint>> monthlyByProductInCategory(String category, Window w) {
        Map<UUID, List<MonthPoint>> out = new HashMap<>();
        jdbc.query("""
                SELECT st.product_id, date_trunc('month', st.txn_date)::date AS month, sum(st.qty) AS units,
                       sum(st.qty * st.unit_price) AS revenue, count(*) AS txns,
                       sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0) AS prev,
                       sum(st.qty) FILTER (WHERE st.unit_price > 0) AS pu
                  FROM sales_transactions st
                 WHERE st.tenant_id = :t
                   AND st.product_id IN (SELECT id FROM products WHERE tenant_id = :t AND category = :c)
                   AND st.txn_date BETWEEN :from AND :to
                 GROUP BY 1, 2
                 ORDER BY 1, 2
                """, Sql.params(w).addValue("c", category), rs -> {
            BigDecimal pu = rs.getBigDecimal("pu");
            BigDecimal avgPrice = pu == null || pu.signum() == 0 ? null
                    : Sql.scaled(PricingMath.div(rs.getBigDecimal("prev"), pu));
            out.computeIfAbsent(Sql.uuid(rs, "product_id"), k -> new ArrayList<>())
                    .add(new MonthPoint(Sql.date(rs, "month"), Sql.moneyOrZero(rs, "units"),
                            Sql.moneyOrZero(rs, "revenue"), rs.getLong("txns"), avgPrice, null));
        });
        return out;
    }

    // ---- T3 price bands ------------------------------------------------------------------

    @Override
    public Optional<PriceBand> priceBand(UUID productId, Window w) {
        return band(productId, null, w);
    }

    @Override
    public Optional<PriceBand> priceBandAtStore(UUID productId, UUID storeId, Window w) {
        return band(productId, storeId, w);
    }

    private Optional<PriceBand> band(UUID productId, UUID storeOrNull, Window w) {
        String where = " WHERE st.tenant_id = :t AND st.product_id = :p"
                + (storeOrNull != null ? " AND st.store_id = :s" : "")
                + " AND st.unit_price > 0 AND st.txn_date BETWEEN :from AND :to";
        MapSqlParameterSource params = Sql.params(w).addValue("p", productId);
        if (storeOrNull != null) {
            params.addValue("s", storeOrNull);
        }
        PriceBand summary = jdbc.query("SELECT count(*) AS n, min(st.unit_price) AS min_price, " + PERCENTILES
                + ", max(st.unit_price) AS max_price FROM sales_transactions st" + where, params, rs -> {
            if (!rs.next() || rs.getLong("n") < MIN_BAND_ROWS) {
                return null;
            }
            return new PriceBand(rs.getLong("n"), Sql.money(rs, "min_price"), Sql.money(rs, "q1"),
                    Sql.money(rs, "median"), Sql.money(rs, "q3"), Sql.money(rs, "max_price"), List.of(), List.of());
        });
        if (summary == null) {
            return Optional.empty();
        }
        List<StoreMedian> medians = storeMedians(productId, w);
        List<Bucket> buckets = jdbc.query("""
                SELECT count(*) FILTER (WHERE st.unit_price < :q1) AS b1,
                       count(*) FILTER (WHERE st.unit_price >= :q1 AND st.unit_price < :median) AS b2,
                       count(*) FILTER (WHERE st.unit_price >= :median AND st.unit_price < :q3) AS b3,
                       count(*) FILTER (WHERE st.unit_price >= :q3) AS b4
                  FROM sales_transactions st
                """ + where,
                params.addValue("q1", summary.q1()).addValue("median", summary.median()).addValue("q3", summary.q3()),
                rs -> {
                    if (!rs.next()) {
                        return List.of();
                    }
                    return List.of(
                            new Bucket(summary.min(), summary.q1(), rs.getLong("b1")),
                            new Bucket(summary.q1(), summary.median(), rs.getLong("b2")),
                            new Bucket(summary.median(), summary.q3(), rs.getLong("b3")),
                            new Bucket(summary.q3(), summary.max(), rs.getLong("b4")));
                });
        return Optional.of(new PriceBand(summary.n(), summary.min(), summary.q1(), summary.median(), summary.q3(),
                summary.max(), medians, buckets));
    }

    /** T3b: the median charged at each branch with three or more priced lines. */
    private List<StoreMedian> storeMedians(UUID productId, Window w) {
        return jdbc.query("""
                SELECT st.store_id, s.store_code,
                       round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY st.unit_price) AS numeric), 4) AS median,
                       count(*) AS n, sum(st.qty) AS units
                  FROM sales_transactions st JOIN stores s ON s.id = st.store_id
                 WHERE st.tenant_id = :t AND st.product_id = :p AND st.unit_price > 0
                   AND st.txn_date BETWEEN :from AND :to
                 GROUP BY st.store_id, s.store_code
                HAVING count(*) >= %d
                 ORDER BY s.store_code
                """.formatted(MIN_STORE_MEDIAN_ROWS), Sql.params(w).addValue("p", productId),
                (rs, i) -> new StoreMedian(Sql.uuid(rs, "store_id"), rs.getString("store_code"),
                        Sql.money(rs, "median"), rs.getLong("n"), Sql.moneyOrZero(rs, "units")));
    }

    @Override
    public Optional<PeerBand> peerBand(UUID productId, UUID storeId, Window w) {
        return peerBand(storeMedians(productId, w), storeId);
    }

    /** Quartiles of the other branches' medians; one other branch gives q1 = q2 = q3. */
    static Optional<PeerBand> peerBand(List<StoreMedian> medians, UUID excludeStore) {
        double[] values = medians.stream()
                .filter(m -> excludeStore == null || !excludeStore.equals(m.storeId()))
                .filter(m -> m.median() != null)
                .mapToDouble(m -> m.median().doubleValue())
                .toArray();
        if (values.length == 0) {
            return Optional.empty();
        }
        double[] q = Stats.quantiles(values);
        return Optional.of(new PeerBand(money(q[0]), money(q[1]), money(q[2]), values.length));
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(Sql.SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public Map<UUID, PriceBand> priceBands(Window w) {
        Map<UUID, List<StoreMedian>> medians = new HashMap<>();
        jdbc.query("""
                SELECT st.product_id, st.store_id, s.store_code,
                       round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY st.unit_price) AS numeric), 4) AS median,
                       count(*) AS n, sum(st.qty) AS units
                  FROM sales_transactions st JOIN stores s ON s.id = st.store_id
                 WHERE st.tenant_id = :t AND st.unit_price > 0 AND st.txn_date BETWEEN :from AND :to
                 GROUP BY st.product_id, st.store_id, s.store_code
                HAVING count(*) >= %d
                 ORDER BY st.product_id, s.store_code
                """.formatted(MIN_STORE_MEDIAN_ROWS), Sql.params(w), rs -> {
            medians.computeIfAbsent(Sql.uuid(rs, "product_id"), k -> new ArrayList<>())
                    .add(new StoreMedian(Sql.uuid(rs, "store_id"), rs.getString("store_code"),
                            Sql.money(rs, "median"), rs.getLong("n"), Sql.moneyOrZero(rs, "units")));
        });
        Map<UUID, PriceBand> out = new HashMap<>();
        jdbc.query("SELECT st.product_id, count(*) AS n, min(st.unit_price) AS min_price, " + PERCENTILES
                + ", max(st.unit_price) AS max_price FROM sales_transactions st"
                + " WHERE st.tenant_id = :t AND st.unit_price > 0 AND st.txn_date BETWEEN :from AND :to"
                + " GROUP BY st.product_id HAVING count(*) >= " + MIN_BAND_ROWS, Sql.params(w), rs -> {
            UUID productId = Sql.uuid(rs, "product_id");
            out.put(productId, new PriceBand(rs.getLong("n"), Sql.money(rs, "min_price"), Sql.money(rs, "q1"),
                    Sql.money(rs, "median"), Sql.money(rs, "q3"), Sql.money(rs, "max_price"),
                    medians.getOrDefault(productId, List.of()), List.of()));
        });
        return out;
    }

    // ---- T4 velocity ---------------------------------------------------------------------

    @Override
    public Velocity velocity(UUID productId, UUID storeIdOrNull, LocalDate today) {
        Window w12 = Window.trailingMonths(today, 12);
        String storePredicate = storeIdOrNull != null ? " AND st.store_id = :s" : "";
        MapSqlParameterSource params = Sql.params(w12).addValue("p", productId);
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        LocalDate firstSale = jdbc.query("SELECT min(st.txn_date) AS first_sale FROM sales_transactions st"
                + " WHERE st.tenant_id = :t AND st.product_id = :p" + storePredicate
                + " AND st.txn_date BETWEEN :from AND :to", params,
                rs -> rs.next() ? Sql.date(rs, "first_sale") : null);
        return jdbc.query("""
                SELECT coalesce(sum(st.qty) FILTER (WHERE st.txn_date >= :r0), 0) AS u_r,
                       count(*) FILTER (WHERE st.txn_date >= :r0) AS n_r,
                       coalesce(sum(st.qty) FILTER (WHERE st.txn_date < :r0), 0) AS u_p,
                       count(*) FILTER (WHERE st.txn_date < :r0) AS n_p
                  FROM sales_transactions st
                 WHERE st.tenant_id = :t AND st.product_id = :p %s AND st.txn_date BETWEEN :p0 AND :to
                """.formatted(storePredicate), params, rs -> {
            rs.next();
            BigDecimal recent = Sql.moneyOrZero(rs, "u_r");
            BigDecimal prior = Sql.moneyOrZero(rs, "u_p");
            int historyDays = firstSale == null ? 0
                    : (int) Math.min(365, ChronoUnit.DAYS.between(firstSale, today));
            return new Velocity(perWeek(recent), perWeek(prior), PricingMath.pct(recent.subtract(prior), prior),
                    rs.getLong("n_r"), rs.getLong("n_p"), historyDays, null);
        });
    }

    static BigDecimal perWeek(BigDecimal units90) {
        return units90.multiply(BigDecimal.valueOf(7)).divide(BigDecimal.valueOf(90), Sql.SCALE, RoundingMode.HALF_UP);
    }

    // ---- seasonality and elasticity ------------------------------------------------------

    @Override
    public Optional<Seasonality> seasonality(UUID productId, LocalDate today) {
        List<MonthPoint> points = monthly(productId, null, 36, today);
        if (points.isEmpty()) {
            return Optional.empty();
        }
        double[] units = points.stream().mapToDouble(p -> p.units().doubleValue()).toArray();
        double[] index = Stats.seasonalIndices(units, points.get(0).month().getMonthValue());
        if (index == null) {
            return Optional.empty();
        }
        int months = (int) points.stream().filter(p -> p.units().signum() > 0).count();
        return Optional.of(new Seasonality(index, months));
    }

    private static final int ELASTICITY_MIN_N = 8;
    private static final double ELASTICITY_MIN_R2 = 0.25;
    private static final int CATEGORY_MIN_N = 24;
    private static final double CATEGORY_MIN_R2 = 0.15;
    private static final double BETA_MIN = -6;
    private static final double BETA_MAX = -0.1;

    @Override
    public Elasticity elasticity(UUID productId, UUID storeIdOrNull, LocalDate today) {
        double[] index = seasonality(productId, today).map(Seasonality::index).orElse(null);
        if (storeIdOrNull != null) {
            Elasticity itemStore = fit(monthly(productId, storeIdOrNull, 24, today), index, Elasticity.ITEM_STORE);
            if (itemStore != null) {
                return itemStore;
            }
        }
        Elasticity item = fit(monthly(productId, null, 24, today), index, Elasticity.ITEM);
        if (item != null) {
            return item;
        }
        String category = jdbc.query("SELECT category FROM products WHERE tenant_id = :t AND id = :p",
                Sql.params().addValue("p", productId), rs -> rs.next() ? rs.getString("category") : null);
        if (category != null) {
            Elasticity pooled = categoryFit(category, Window.trailingMonths(today, 24));
            if (pooled != null) {
                return pooled;
            }
        }
        return Elasticity.defaultValue();
    }

    /** Log-log OLS over months with units and a price; accepted at n ≥ 8, r² ≥ 0.25, −6 ≤ β ≤ −0.1. */
    private static Elasticity fit(List<MonthPoint> points, double[] index, String basis) {
        List<double[]> rows = new ArrayList<>();
        for (MonthPoint p : points) {
            if (p.units().signum() > 0 && p.avgPrice() != null && p.avgPrice().signum() > 0) {
                double units = p.units().doubleValue();
                if (index != null) {
                    units = units / index[p.month().getMonthValue() - 1];
                }
                rows.add(new double[] {p.avgPrice().doubleValue(), units});
            }
        }
        double[] prices = rows.stream().mapToDouble(r -> r[0]).toArray();
        double[] units = rows.stream().mapToDouble(r -> r[1]).toArray();
        Stats.Ols fit = Stats.olsLogLog(prices, units);
        if (!fit.available() || fit.n() < ELASTICITY_MIN_N || fit.r2() < ELASTICITY_MIN_R2
                || fit.coefficient() < BETA_MIN || fit.coefficient() > BETA_MAX) {
            return null;
        }
        return elasticity(fit, basis);
    }

    private Elasticity categoryFit(String category, Window w24) {
        Map<UUID, List<MonthPoint>> byProduct = monthlyByProductInCategory(category, w24);
        List<double[]> xs = new ArrayList<>();
        List<double[]> ys = new ArrayList<>();
        for (List<MonthPoint> points : byProduct.values()) {
            List<double[]> rows = new ArrayList<>();
            for (MonthPoint p : points) {
                if (p.units().signum() > 0 && p.avgPrice() != null && p.avgPrice().signum() > 0) {
                    rows.add(new double[] {Math.log(p.avgPrice().doubleValue()), Math.log(p.units().doubleValue())});
                }
            }
            if (rows.size() >= 2) {
                xs.add(rows.stream().mapToDouble(r -> r[0]).toArray());
                ys.add(rows.stream().mapToDouble(r -> r[1]).toArray());
            }
        }
        Stats.Ols fit = Stats.pooledDemeaned(xs.toArray(double[][]::new), ys.toArray(double[][]::new));
        if (!fit.available() || fit.n() < CATEGORY_MIN_N || fit.r2() < CATEGORY_MIN_R2
                || fit.coefficient() < BETA_MIN || fit.coefficient() > BETA_MAX) {
            return null;
        }
        return elasticity(fit, Elasticity.CATEGORY);
    }

    private static Elasticity elasticity(Stats.Ols fit, String basis) {
        return new Elasticity(money(fit.coefficient()), money(fit.r2()), fit.n(), basis, money(fit.stdError()));
    }

    // ---- movers, last sale, pairs, co-purchase -------------------------------------------

    private static final BigDecimal MOVER_MIN_PRIOR_UNITS = BigDecimal.valueOf(20);

    @Override
    public List<Mover> topMovers(int limit, LocalDate today) {
        List<Mover> movers = new ArrayList<>();
        for (PairStats pair : pairStats(Window.trailingMonths(today, 12))) {
            if (pair.unitsPrior90().compareTo(MOVER_MIN_PRIOR_UNITS) < 0) {
                continue;
            }
            BigDecimal trend = PricingMath.pct(pair.units90().subtract(pair.unitsPrior90()), pair.unitsPrior90());
            if (trend == null) {
                continue;
            }
            movers.add(new Mover(pair.productId(), pair.itemNumber(), pair.storeId(), pair.storeCode(),
                    pair.units90(), pair.unitsPrior90(), trend, pair.w12().revenue()));
        }
        movers.sort(Comparator.comparing((Mover m) -> m.trendPct().abs()).reversed()
                .thenComparing(Mover::itemNumber));
        return movers.size() > limit ? List.copyOf(movers.subList(0, limit)) : movers;
    }

    @Override
    public Optional<LocalDate> lastSale(UUID productId, UUID storeId) {
        return Optional.ofNullable(jdbc.query(
                "SELECT max(st.txn_date) AS last_sale FROM sales_transactions st"
                        + " WHERE st.tenant_id = :t AND st.product_id = :p AND st.store_id = :s",
                Sql.params().addValue("p", productId).addValue("s", storeId),
                rs -> rs.next() ? Sql.date(rs, "last_sale") : null));
    }

    @Override
    public List<PairStats> pairStats(Window w) {
        return jdbc.query("SELECT st.product_id, p.item_number, st.store_id, s.store_code, " + T1_AGG + """
                     , coalesce(sum(st.qty) FILTER (WHERE st.txn_date >= :r0), 0) AS u90,
                       coalesce(sum(st.qty) FILTER (WHERE st.txn_date BETWEEN :p0 AND :r1), 0) AS u90p,
                       sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0 AND st.txn_date >= :d30)
                           / nullif(sum(st.qty) FILTER (WHERE st.unit_price > 0 AND st.txn_date >= :d30), 0) AS avg_price_30,
                       sum(st.qty * st.unit_price) FILTER (WHERE st.unit_price > 0 AND st.txn_date BETWEEN :p0 AND :r1)
                           / nullif(sum(st.qty) FILTER (WHERE st.unit_price > 0 AND st.txn_date BETWEEN :p0 AND :r1), 0) AS avg_price_90p
                  FROM sales_transactions st
                  JOIN products p ON p.id = st.product_id
                  LEFT JOIN stores s ON s.id = st.store_id
                 WHERE st.tenant_id = :t AND st.txn_date BETWEEN :from AND :to
                 GROUP BY st.product_id, p.item_number, st.store_id, s.store_code
                 ORDER BY p.item_number, s.store_code
                """, Sql.params(w), (rs, i) -> {
            UUID storeId = Sql.uuid(rs, "store_id");
            return new PairStats(Sql.uuid(rs, "product_id"), rs.getString("item_number"), storeId,
                    storeId == null ? GroupStats.NO_BRANCH : rs.getString("store_code"), readStats(rs),
                    Sql.moneyOrZero(rs, "u90"), Sql.moneyOrZero(rs, "u90p"), Sql.money(rs, "avg_price_30"),
                    Sql.money(rs, "avg_price_90p"));
        });
    }

    @Override
    public List<CoPurchase> coPurchased(UUID productId, int limit, Window w) {
        return jdbc.query("""
                WITH b AS (SELECT DISTINCT coalesce(st.customer_id::text, lower(st.customer_code)) AS ck,
                                  st.store_id, st.txn_date
                             FROM sales_transactions st
                            WHERE st.tenant_id = :t AND st.product_id = :p AND st.txn_date BETWEEN :from AND :to
                              AND (st.customer_id IS NOT NULL OR st.customer_code IS NOT NULL)),
                base AS (SELECT count(*) AS n FROM b)
                SELECT o.product_id, p.item_number, p.short_name,
                       count(DISTINCT (b.ck, b.store_id, b.txn_date)) AS orders, (SELECT n FROM base) AS base
                  FROM b
                  JOIN sales_transactions o
                    ON o.tenant_id = :t AND o.txn_date BETWEEN :from AND :to AND o.txn_date = b.txn_date
                   AND o.store_id IS NOT DISTINCT FROM b.store_id
                   AND coalesce(o.customer_id::text, lower(o.customer_code)) = b.ck
                   AND o.product_id <> :p
                  JOIN products p ON p.id = o.product_id
                 GROUP BY o.product_id, p.item_number, p.short_name
                 ORDER BY orders DESC, p.item_number
                 LIMIT :limit
                """, Sql.params(w).addValue("p", productId).addValue("limit", limit), (rs, i) -> {
            long orders = rs.getLong("orders");
            long base = rs.getLong("base");
            BigDecimal attach = PricingMath.pct(BigDecimal.valueOf(orders), BigDecimal.valueOf(base));
            return new CoPurchase(Sql.uuid(rs, "product_id"), rs.getString("item_number"), rs.getString("short_name"),
                    orders, base, attach == null ? null : attach.setScale(1, RoundingMode.HALF_UP));
        });
    }
}

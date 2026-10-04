package com.aatlas.demandmodel.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.tribuo.regression.xgboost.XGBoostRegressionTrainer;
import org.tribuo.regression.xgboost.XGBoostRegressionTrainer.RegressionType;

/**
 * The forecasters against each other on a tenant's exported sales.
 *
 * <p>Not a test of the code: a measurement, run by hand when a tenant's sales are exported to a folder.
 * {@code -Dbakeoff.dir} holds {@code sales.tsv} (item, store, category, week, units, average price, average
 * cost) and {@code competitors.tsv} (item, observed, price), tab separated, as {@link TrainingRows} reads them;
 * {@code -Dbakeoff.lastWeek} is the Monday of the last complete week. The report is written to
 * {@code report.txt} beside them.
 *
 * <p>{@code -Dbakeoff.chronos} is the URL of a running {@code scripts/chronos_server.py}; with it set, Chronos
 * is scored beside the rest.
 *
 * <p>Three questions. Which forecaster misses least, at three cuts eight weeks apart. Whether a way of choosing
 * a forecaster means anything: what it chose at one cut is scored on the eight weeks after, which it had not
 * seen. And whether gradient boosting reads price better than the forest, on simulated sales whose price
 * sensitivity is known.
 */
@EnabledIfSystemProperty(named = "bakeoff.dir", matches = ".+")
class ModelBakeOffTest {

    private static final double PLANTED = -1.6;
    private static final double MIN = DemandTrainer.MIN_HOLDOUT_UNITS;

    private static DemandTrainer.Learner boosted(int trees, double eta, int depth, double minChild, double rows,
            double columns) {
        return s -> new XGBoostRegressionTrainer(RegressionType.LINEAR, trees, eta, 0, depth, minChild, rows, columns,
                1, 0, 2, true, s.seed());
    }

    /** A way of choosing a forecaster for a pair from one run's report: the forecaster's key, or null for none. */
    private interface Rule extends Function<DemandTrainer.PairEval, String> {
    }

    private static Map<String, Rule> rules() {
        Map<String, Rule> m = new LinkedHashMap<>();
        m.put("twice in a row (in use)", e -> e.forecastUsable() ? e.forecastMethod() : null);
        m.put("once, any method", e -> {
            String best = null;
            double least = Double.MAX_VALUE;
            for (Map.Entry<String, DemandTrainer.MethodEval> me : e.methods().entrySet()) {
                DemandTrainer.Score later = me.getValue().later();
                if (later.beats(MIN) && later.errorShare() < least) {
                    least = later.errorShare();
                    best = me.getKey();
                }
            }
            return best;
        });
        m.put("once, forest only", e -> e.methods().get(Smoothers.FOREST).later().beats(MIN) ? Smoothers.FOREST : null);
        for (String method : List.of(Smoothers.FOREST, Smoothers.SES, Smoothers.SBA, Smoothers.TSB,
                Smoothers.CHRONOS)) {
            m.put("always " + method, e -> e.methods().containsKey(method) ? method : null);
        }
        return m;
    }

    @Test
    @DisplayName("the forecasters against each other, on exported sales and on a planted answer")
    void bakeOff() throws IOException {
        Path dir = Path.of(System.getProperty("bakeoff.dir"));
        LocalDate lastWeek = LocalDate.parse(System.getProperty("bakeoff.lastWeek"));
        List<WeeklyGrid.SalesWeek> sales = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("sales.tsv"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f.length < 7) {
                continue;
            }
            sales.add(new WeeklyGrid.SalesWeek(f[0], f[1], f[2].isEmpty() ? null : f[2], LocalDate.parse(f[3]),
                    Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6])));
        }
        List<WeeklyGrid.CompetitorObs> comps = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("competitors.tsv"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                continue;
            }
            comps.add(new WeeklyGrid.CompetitorObs(f[0], LocalDate.parse(f[1]), Double.parseDouble(f[2])));
        }

        StringBuilder out = new StringBuilder();
        DemandTrainer.Settings settings = DemandTrainer.Settings.defaults();
        out.append(String.format(Locale.ROOT, "%d sales weeks, %d competitor prices, last complete week %s%n",
                sales.size(), comps.size(), lastWeek));

        String chronosUrl = System.getProperty("bakeoff.chronos", "");
        DemandTrainer.Outside outside = new ChronosClient(chronosUrl, 300).orNull();
        out.append(outside == null ? "Chronos: not in this run\n" : "Chronos: " + chronosUrl + "\n");
        Map<Integer, DemandTrainer.Result> runs = new LinkedHashMap<>();
        out.append("\nFOUR-WEEK MISS BY FORECASTER: share of units sold over the last eight weeks of each cut\n");
        for (int back = 16; back >= 0; back -= 8) {
            LocalDate cut = lastWeek.minusWeeks(back);
            WeeklyGrid.Grid grid = WeeklyGrid.build(sales, comps, cut, 12);
            DemandTrainer.Result r = DemandTrainer.train(grid, settings, DemandTrainer.FOREST, outside);
            runs.put(back, r);
            out.append(String.format(Locale.ROOT, "%nweeks ending %s: %d pairs, %d rows, %d ms%n", cut.plusDays(6),
                    grid.contexts().size(), grid.rows().size(), r.millis()));
            out.append(String.format(Locale.ROOT, "    %-10s %10s %10s %10s %10s %8s%n", "", "earlier", "naive", "later",
                    "naive", "in use"));
            r.pooled().forEach((method, w) -> out.append(String.format(Locale.ROOT,
                    "    %-10s %9.1f%% %9.1f%% %9.1f%% %9.1f%% %8d%n", method, 100 * w[0].errorShare(),
                    100 * w[0].naiveShare(), 100 * w[1].errorShare(), 100 * w[1].naiveShare(),
                    r.evals().values().stream()
                            .filter(e -> e.forecastUsable() && method.equals(e.forecastMethod())).count())));
        }

        out.append("\nCHOSEN, THEN SCORED ON THE NEXT EIGHT WEEKS (two steps: 16 back to 8 back, 8 back to now)\n");
        out.append(String.format(Locale.ROOT, "  %-24s %7s %10s %10s %12s%n", "", "pairs", "chosen", "naive",
                "still ahead"));
        for (Map.Entry<String, Rule> rule : rules().entrySet()) {
            DemandTrainer.Score total = DemandTrainer.Score.NONE;
            int pairs = 0;
            int ahead = 0;
            for (int earlier = 16; earlier >= 8; earlier -= 8) {
                Map<String, DemandTrainer.PairEval> next = runs.get(earlier - 8).evals();
                for (Map.Entry<String, DemandTrainer.PairEval> e : runs.get(earlier).evals().entrySet()) {
                    String method = rule.getValue().apply(e.getValue());
                    DemandTrainer.PairEval after = next.get(e.getKey());
                    if (method == null || after == null) {
                        continue;
                    }
                    DemandTrainer.Score s = after.methods().get(method).later();
                    if (!s.enough(MIN)) {
                        continue;
                    }
                    pairs++;
                    ahead += s.errorShare() < s.naiveShare() ? 1 : 0;
                    total = total.plus(s);
                }
            }
            out.append(String.format(Locale.ROOT, "  %-24s %7d %9.1f%% %9.1f%% %12d%n", rule.getKey(), pairs,
                    100 * total.errorShare(), 100 * total.naiveShare(), ahead));
        }

        Map<String, DemandTrainer.Learner> learners = new LinkedHashMap<>();
        learners.put("forest (in use)", DemandTrainer.FOREST);
        learners.put("boosted, shallow", boosted(500, 0.03, 3, 8, 0.8, 0.8));
        learners.put("boosted, medium", boosted(300, 0.05, 4, 5, 0.8, 0.8));
        learners.put("boosted, deep", boosted(150, 0.10, 6, 3, 0.8, 0.6));
        out.append(String.format(Locale.ROOT,
                "%nPLANTED ANSWER: simulated sales whose true price sensitivity is %.1f%n", PLANTED));
        out.append(String.format(Locale.ROOT, "  %-18s %12s %12s %12s %14s%n", "", "mean read", "mean miss",
                "worst miss", "4wk vs naive"));
        for (Map.Entry<String, DemandTrainer.Learner> l : learners.entrySet()) {
            double sum = 0;
            double miss = 0;
            double worst = 0;
            int n = 0;
            DemandTrainer.Score forest = DemandTrainer.Score.NONE;
            for (long seed : new long[] {7, 11, 23}) {
                int weeks = 80;
                WeeklyGrid.Grid grid = WeeklyGrid.build(DemandTrainerTest.simulate(weeks, seed), List.of(),
                        LocalDate.of(2024, 9, 2).plusWeeks(weeks - 1), 12);
                for (DemandTrainer.PairEval e : DemandTrainer.train(grid, settings, l.getValue()).evals().values()) {
                    sum += e.elasticity();
                    miss += Math.abs(e.elasticity() - PLANTED);
                    worst = Math.max(worst, Math.abs(e.elasticity() - PLANTED));
                    n++;
                    DemandTrainer.MethodEval ev = e.methods().get(Smoothers.FOREST);
                    forest = forest.plus(ev.earlier()).plus(ev.later());
                }
            }
            out.append(String.format(Locale.ROOT, "  %-18s %12.2f %12.2f %12.2f %6.1f%%/%5.1f%%%n", l.getKey(), sum / n,
                    miss / n, worst, 100 * forest.errorShare(), 100 * forest.naiveShare()));
        }
        Files.writeString(dir.resolve("report.txt"), out.toString(), StandardCharsets.UTF_8);
    }
}

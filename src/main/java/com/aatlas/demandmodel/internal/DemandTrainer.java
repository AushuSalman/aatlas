package com.aatlas.demandmodel.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Prediction;
import org.tribuo.Trainer;
import org.tribuo.common.tree.RandomForestTrainer;
import org.tribuo.impl.ArrayExample;
import org.tribuo.provenance.SimpleDataSourceProvenance;
import org.tribuo.regression.RegressionFactory;
import org.tribuo.regression.Regressor;
import org.tribuo.regression.ensemble.AveragingCombiner;
import org.tribuo.regression.rtree.CARTRegressionTrainer;
import org.tribuo.regression.rtree.impurity.MeanSquaredError;

/**
 * Fits the demand models and scores them honestly.
 *
 * <p>Two forests on the same weekly rows. The <em>forecast</em> model sees the previous weeks'
 * demand and answers "how many next week": it is scored by leaving the last
 * {@code holdoutWeeks} of every pair out, forecasting them one week ahead, and comparing the
 * error with a naive baseline that saw the same lags (the trailing eight-week mean). The
 * <em>response</em> model does not see the lags and answers "how many at this price, once
 * demand has settled": its probed slope is the price sensitivity, and its profit curve is what
 * the profit-max step needs. The forecast model is fitted on everything to serve, and once for
 * each back-test without the weeks that back-test holds out.
 *
 * <p>The models are random forests of regression trees (Tribuo): no native binaries, seconds
 * to train at thousands of rows, and at ease with one-hot items beside continuous prices. Price
 * sensitivity is read out rather than assumed: the pair's settled demand is predicted at seven
 * prices from 15% under to 15% over its last price, and the log-log slope through those
 * predictions is the elasticity. The price read is usable for a pair when the forecast model beat the
 * baseline on its held-out weeks by a margin on at least {@value #MIN_HOLDOUT_UNITS} units and the slope
 * is negative; a slope that says units rise with price is reported but never used.
 *
 * <p>The four-week demand forecast is a contest. The forest stands beside the classical forecasters in
 * {@link Smoothers}, and each is scored on every pair over two back-tests in a row (the last
 * {@code holdoutWeeks} and the same number before them), against the recent average held flat. A forecaster
 * is used for a pair only when it beat the recent average there in both back-tests; of several that did,
 * the one that missed least. One win is not asked for because one win is mostly luck: on the sales this
 * was measured on, pairs chosen on a single back-test went on to tie the recent average, and pairs chosen
 * on two went on to beat it. With no such forecaster the rule engine's own pace stands. A forecaster that
 * lives outside this process ({@link Outside}: a pretrained model behind HTTP) joins the same contest on the
 * same terms when one is given, and is left out of it when it does not answer.
 *
 * <p>Pure: a grid in, models and a report out.
 */
final class DemandTrainer {

    /** The model's error must be this much under the baseline's (as a share of units) to count as a win. */
    static final double BEAT_MARGIN = 0.02;
    /** Units sold over the held-out weeks before an error figure means anything. */
    static final double MIN_HOLDOUT_UNITS = 5;
    /** Rows before a forest is worth fitting at all. */
    static final int MIN_ROWS = 60;
    /** Weeks of history before the season features are trusted: well over one cycle, else they index the calendar. */
    static final int SEASON_MIN_WEEKS = 78;
    /** The price grid, as multiples of the pair's last price, the elasticity is read from. */
    static final double[] PROBE = {0.85, 0.90, 0.95, 1.0, 1.05, 1.10, 1.15};
    /** A pair's calibration is kept inside this range: outside it the fit is too poor to scale. */
    static final double CALIBRATION_MIN = 0.5;
    static final double CALIBRATION_MAX = 4;
    /** Weeks ahead the demand forecast is read over: a month smooths a lumpy week. */
    static final int HORIZON_WEEKS = 4;
    /** Weeks of a pair's own history before a forecast of it is scored. */
    static final int MIN_HISTORY_WEEKS = 4;
    static final double ELASTICITY_MIN = -6;
    static final double ELASTICITY_MAX = 2;

    private static final RegressionFactory FACTORY = new RegressionFactory();
    private static final Regressor UNKNOWN = FACTORY.getUnknownOutput();

    private DemandTrainer() {
    }

    record Settings(int trees, int maxDepth, int minChildWeight, double featureFraction, int holdoutWeeks, long seed,
            boolean itemOneHot) {

        static Settings defaults() {
            return new Settings(100, 10, 5, 0.6, 8, 42L, true);
        }

        Settings withHoldoutWeeks(int weeks) {
            return new Settings(trees, maxDepth, minChildWeight, featureFraction, weeks, seed, itemOneHot);
        }

        Settings withTrees(int n) {
            return new Settings(n, maxDepth, minChildWeight, featureFraction, holdoutWeeks, seed, itemOneHot);
        }
    }

    /**
     * A forecaster's four-week miss over one back-test, summed over its blocks: the actual weekly means, the
     * forecaster's absolute miss and the recent average's.
     */
    record Score(double units, double error, double naive) {

        static final Score NONE = new Score(0, 0, 0);

        Score plus(Score o) {
            return new Score(units + o.units, error + o.error, naive + o.naive);
        }

        boolean enough(double minUnits) {
            return units * HORIZON_WEEKS >= minUnits;
        }

        double errorShare() {
            return units > 0 ? error / units : Double.NaN;
        }

        double naiveShare() {
            return units > 0 ? naive / units : Double.NaN;
        }

        boolean beats(double minUnits) {
            return enough(minUnits) && errorShare() < naiveShare() - BEAT_MARGIN;
        }

        boolean loses(double minUnits) {
            return enough(minUnits) && errorShare() > naiveShare() + BEAT_MARGIN;
        }
    }

    /** One forecaster on one pair: its two back-tests and the weekly units it expects over the next four weeks. */
    record MethodEval(Score earlier, Score later, double nextUnits) {
    }

    /**
     * One pair's score. Errors are shares of the units sold over the held-out weeks (0.2 = 20%).
     *
     * @param usable the price read may be used: the one-week forecast beat the baseline and the probed slope is negative
     * @param horizonError the four-week miss of the pair's forecaster over both back-tests, as a share of units
     * @param horizonBaseline the same for the recent average held flat
     * @param forecastUsable a forecaster beat the recent average on this pair in both back-tests: the demand
     *        step may read it
     * @param horizonUnits the weekly units that forecaster expects over the next four weeks at the last price
     * @param trailingUnits the pair's actual weekly units over its last eight weeks
     * @param forecastMethod the forecaster the figures are for: the proven one, else the one that missed least
     * @param forecastNote why the forecast is or is not in use, in a sentence
     * @param methods every forecaster's back-tests on this pair
     */
    record PairEval(int weeks, double holdoutUnits, double errorModel, double errorBaseline, boolean beats,
            boolean usable, double elasticity, double forecastNext, double settledNext, String note,
            double horizonError, double horizonBaseline, boolean forecastUsable, double horizonUnits,
            double trailingUnits, double calibration, String forecastMethod, String forecastNote,
            Map<String, MethodEval> methods) {
    }

    /**
     * @param forecast the model that sees the lags: next week's units
     * @param response the model that does not: settled units at a price, and the elasticity
     * @param pooled each forecaster's two back-tests summed across every pair (earlier, then later): the report's
     *        view of which forecaster misses least overall; no pair is chosen from it
     * @param methodMillis how long each forecaster took in this run, by key: the forest's fits and roll-forwards,
     *        each smoother's tuning and back-tests, the outside forecaster's calls
     */
    record Result(Model<Regressor> forecast, Model<Regressor> response, Map<String, PairEval> evals, int rows,
            int trainRows, long millis, boolean season, Map<String, Score[]> pooled, Map<String, Long> methodMillis) {
    }

    /**
     * What fits a model on the rows. Production uses {@link #FOREST}; another learner is passed only to score it
     * against the forest on the same rows, the same held-out weeks and the same gates.
     */
    @FunctionalInterface
    interface Learner {
        Trainer<Regressor> trainer(Settings s);
    }

    /**
     * A forecaster that is not fitted here: a pretrained model asked from outside. It is given each series up
     * to the week it must forecast from and nothing after.
     */
    @FunctionalInterface
    interface Outside {
        /**
         * @param series weekly units, oldest first, one per forecast wanted
         * @return the weekly units expected over the next {@code horizon} weeks, one per series in order; null
         *         when it cannot answer, which leaves it out of the contest
         */
        double[] forecast(List<double[]> series, int horizon);
    }

    static final Learner FOREST = s -> new RandomForestTrainer<>(
            new CARTRegressionTrainer(s.maxDepth(), (float) s.minChildWeight(), 0f, (float) s.featureFraction(), false,
                    new MeanSquaredError(), s.seed()),
            new AveragingCombiner(), s.trees(), s.seed());

    /** Whether a model was fitted with the season features: serving must build the same features. */
    static boolean trainedWithSeason(Model<Regressor> model) {
        return model.getFeatureIDMap().get(Features.SEASON_SIN) != null;
    }

    static Result train(WeeklyGrid.Grid grid, Settings s) {
        return train(grid, s, FOREST, null);
    }

    static Result train(WeeklyGrid.Grid grid, Settings s, Learner learner) {
        return train(grid, s, learner, null);
    }

    static Result train(WeeklyGrid.Grid grid, Settings s, Learner learner, Outside outside) {
        long t0 = System.nanoTime();
        boolean season = grid.spanWeeks() >= SEASON_MIN_WEEKS;
        // The upload's first week is partial unless it began on a Monday: never a week to learn from.
        List<WeeklyGrid.Row> rows = grid.rows().stream().filter(r -> !r.week().equals(grid.firstWeek())).toList();
        if (rows.size() < MIN_ROWS) {
            throw new IllegalStateException("only " + rows.size() + " weekly rows; " + MIN_ROWS + " needed");
        }
        LocalDate cutoff = grid.lastWeek().minusWeeks(s.holdoutWeeks());
        LocalDate earlierCutoff = cutoff.minusWeeks(s.holdoutWeeks());
        List<WeeklyGrid.Row> train = new ArrayList<>();
        List<WeeklyGrid.Row> earlierTrain = new ArrayList<>();
        Map<String, List<WeeklyGrid.Row>> holdout = new LinkedHashMap<>();
        Map<String, List<WeeklyGrid.Row>> byPair = new LinkedHashMap<>();
        for (WeeklyGrid.Row r : rows) {
            byPair.computeIfAbsent(r.pair(), k -> new ArrayList<>()).add(r);
            if (r.week().isAfter(cutoff)) {
                holdout.computeIfAbsent(r.pair(), k -> new ArrayList<>()).add(r);
            } else {
                train.add(r);
                if (!r.week().isAfter(earlierCutoff)) {
                    earlierTrain.add(r);
                }
            }
        }
        if (train.size() < MIN_ROWS) {
            throw new IllegalStateException("only " + train.size() + " rows before the held-out weeks; " + MIN_ROWS
                    + " needed");
        }
        Map<String, Long> nanos = new LinkedHashMap<>();
        long fitStart = System.nanoTime();
        Model<Regressor> scorer = fit(train, s, season, true, learner);
        Model<Regressor> forecast = fit(rows, s, season, true, learner);
        Model<Regressor> response = fit(rows, s, season, false, learner);
        // The earlier back-test needs a forest that saw nothing from it on. Without enough rows that far back
        // the forest is not scored there, and so cannot be proven.
        Model<Regressor> earlierScorer = earlierTrain.size() >= MIN_ROWS ? fit(earlierTrain, s, season, true, learner)
                : null;
        // Fitted on log(1 + units), a forest under-states the mean of lumpy sales. Each pair's forecasts are
        // scaled by actual over predicted units on the rows the model was fitted on; a scorer's scale comes
        // from the rows before its held-out weeks only, so the score stays honest.
        Map<String, Double> scorerScale = calibration(scorer, train, s.itemOneHot(), season);
        Map<String, Double> scale = calibration(forecast, rows, s.itemOneHot(), season);
        Map<String, Double> earlierScale = earlierScorer == null ? Map.of()
                : calibration(earlierScorer, earlierTrain, s.itemOneHot(), season);
        nanos.merge(Smoothers.FOREST, System.nanoTime() - fitStart, Long::sum);

        long outsideStart = System.nanoTime();
        Map<String, Map<Integer, Double>> outsideLevels = outsideLevels(byPair, s, outside);
        if (outside != null) {
            nanos.merge(Smoothers.CHRONOS, System.nanoTime() - outsideStart, Long::sum);
        }

        Map<String, PairEval> evals = new LinkedHashMap<>();
        Map<String, Score[]> pooled = new LinkedHashMap<>();
        for (Map.Entry<String, WeeklyGrid.Context> e : grid.contexts().entrySet()) {
            WeeklyGrid.Context c = e.getValue();
            double actual = 0;
            double errModel = 0;
            double errBase = 0;
            List<WeeklyGrid.Row> held = holdout.getOrDefault(e.getKey(), List.of());
            double scorerK = scorerScale.getOrDefault(e.getKey(), 1.0);
            double k = scale.getOrDefault(e.getKey(), 1.0);
            for (WeeklyGrid.Row r : held) {
                double m = scorerK * predictUnits(scorer, Features.at(r, s.itemOneHot(), season, true));
                double b = mean(r.lags());
                actual += r.units();
                errModel += Math.abs(m - r.units());
                errBase += Math.abs(b - r.units());
            }
            double elasticity = elasticity(response, c, s.itemOneHot(), season);
            double next = k * predictUnits(forecast, Features.at(c, c.lastPrice(), s.itemOneHot(), season, true));
            double settled = settledUnits(response, c, c.lastPrice(), s.itemOneHot(), season);
            boolean enough = actual >= MIN_HOLDOUT_UNITS;
            double em = enough ? errModel / actual : Double.NaN;
            double eb = enough ? errBase / actual : Double.NaN;
            boolean beats = enough && em < eb - BEAT_MARGIN;
            boolean rightSign = elasticity < 0;
            boolean usable = beats && rightSign;
            String note = !enough ? "too few sales in the held-out weeks to score"
                    : !beats ? "did not beat the trailing-mean baseline on the held-out weeks"
                    : !rightSign ? "beat the baseline, but the probed price response is not downward"
                    : "beat the baseline on the held-out weeks";

            // The demand step reads a month ahead, so that is what every forecaster is scored on: four weeks
            // forecast from where a block begins, against the recent average held flat, over two back-tests.
            List<WeeklyGrid.Row> pr = byPair.getOrDefault(e.getKey(), List.of());
            int n = pr.size();
            int laterFrom = Math.max(0, n - s.holdoutWeeks());
            int earlierFrom = Math.max(0, n - 2 * s.holdoutWeeks());
            double[] y = pr.stream().mapToDouble(WeeklyGrid.Row::units).toArray();
            Map<String, MethodEval> methods = new LinkedHashMap<>();
            double earlierK = earlierScale.getOrDefault(e.getKey(), 1.0);
            double[] flat = new double[HORIZON_WEEKS];
            java.util.Arrays.fill(flat, c.lastPrice());
            long forestStart = System.nanoTime();
            methods.put(Smoothers.FOREST, new MethodEval(
                    earlierScorer == null ? Score.NONE : scoreBlocks(pr, earlierFrom, laterFrom,
                            o -> rollForward(earlierScorer, c, pr.get(o).lags(), pr.get(o).compMedian(), prices(pr, o),
                                    pr.get(o).week(), earlierK, s.itemOneHot(), season)),
                    scoreBlocks(pr, laterFrom, n,
                            o -> rollForward(scorer, c, pr.get(o).lags(), pr.get(o).compMedian(), prices(pr, o),
                                    pr.get(o).week(), scorerK, s.itemOneHot(), season)),
                    rollForward(forecast, c, c.lags(), c.compMedian(), flat, c.lastWeek().plusWeeks(1), k,
                            s.itemOneHot(), season)));
            nanos.merge(Smoothers.FOREST, System.nanoTime() - forestStart, Long::sum);
            for (Smoothers.Method m : Smoothers.METHODS) {
                long smootherStart = System.nanoTime();
                // A smoother's settings are chosen from the weeks before the back-test it is scored on.
                double[] before = Smoothers.levels(m.key(), y, Smoothers.best(m, y, earlierFrom, HORIZON_WEEKS));
                double[] between = Smoothers.levels(m.key(), y, Smoothers.best(m, y, laterFrom, HORIZON_WEEKS));
                double[] now = Smoothers.levels(m.key(), y, Smoothers.best(m, y, n, HORIZON_WEEKS));
                methods.put(m.key(), new MethodEval(scoreBlocks(pr, earlierFrom, laterFrom, o -> before[o]),
                        scoreBlocks(pr, laterFrom, n, o -> between[o]), now[n]));
                nanos.merge(m.key(), System.nanoTime() - smootherStart, Long::sum);
            }
            Map<Integer, Double> asked = outsideLevels.get(e.getKey());
            if (asked != null && asked.containsKey(n)) {
                IntToDoubleFunction answer = o -> asked.getOrDefault(o, Double.NaN);
                methods.put(Smoothers.CHRONOS, new MethodEval(scoreBlocks(pr, earlierFrom, laterFrom, answer),
                        scoreBlocks(pr, laterFrom, n, answer), asked.get(n)));
            }
            methods.forEach((method, scored) -> {
                Score[] sum = pooled.computeIfAbsent(method, key -> new Score[] {Score.NONE, Score.NONE});
                sum[0] = sum[0].plus(scored.earlier());
                sum[1] = sum[1].plus(scored.later());
            });
            Verdict v = verdict(methods, AUTO);
            evals.put(e.getKey(), new PairEval(c.weeks(), actual, em, eb, beats, usable, elasticity, next, settled,
                    note, v.errorShare(), v.naiveShare(), v.usable(), v.nextUnits(), mean(c.lags()), k, v.method(),
                    v.note(), methods));
        }
        Map<String, Long> methodMillis = new LinkedHashMap<>();
        nanos.forEach((k2, v) -> methodMillis.put(k2, Math.max(1, v / 1_000_000)));
        return new Result(forecast, response, evals, rows.size(), train.size(), (System.nanoTime() - t0) / 1_000_000,
                season, pooled, methodMillis);
    }

    /**
     * The outside forecaster's answers for every pair, asked in one go: a forecast from the start of each
     * back-test block and one from the end of the series. Keyed by pair, then by how many of the pair's weeks
     * the forecast had seen. Empty when there is no outside forecaster or it did not answer.
     */
    static Map<String, Map<Integer, Double>> outsideLevels(Map<String, List<WeeklyGrid.Row>> byPair, Settings s,
            Outside outside) {
        Map<String, Map<Integer, Double>> levels = new LinkedHashMap<>();
        if (outside == null) {
            return levels;
        }
        List<double[]> series = new ArrayList<>();
        List<String> pairOf = new ArrayList<>();
        List<Integer> seenOf = new ArrayList<>();
        for (Map.Entry<String, List<WeeklyGrid.Row>> p : byPair.entrySet()) {
            double[] y = p.getValue().stream().mapToDouble(WeeklyGrid.Row::units).toArray();
            int n = y.length;
            int laterFrom = Math.max(0, n - s.holdoutWeeks());
            int earlierFrom = Math.max(0, n - 2 * s.holdoutWeeks());
            // The starts scoreBlocks reads in each back-test, then the end of the series for the forecast served.
            List<Integer> starts = new ArrayList<>();
            for (int start = earlierFrom; start + HORIZON_WEEKS <= laterFrom; start += HORIZON_WEEKS) {
                starts.add(start);
            }
            for (int start = laterFrom; start + HORIZON_WEEKS <= n; start += HORIZON_WEEKS) {
                starts.add(start);
            }
            starts.removeIf(start -> start < MIN_HISTORY_WEEKS);
            if (n > 0) {
                starts.add(n);
            }
            for (int start : starts) {
                series.add(Arrays.copyOf(y, start));
                pairOf.add(p.getKey());
                seenOf.add(start);
            }
        }
        double[] answers = series.isEmpty() ? null : outside.forecast(series, HORIZON_WEEKS);
        if (answers == null || answers.length != series.size()) {
            return levels;
        }
        for (int i = 0; i < answers.length; i++) {
            levels.computeIfAbsent(pairOf.get(i), k -> new LinkedHashMap<>()).put(seenOf.get(i), answers[i]);
        }
        return levels;
    }

    /** No one model chosen: each pair reads the forecaster proven on it. */
    static final String AUTO = "auto";

    /**
     * A pair's four-week forecast under a choice of model.
     *
     * @param method the forecaster the figures are for
     * @param usable the demand step may read it
     * @param proven that forecaster beat the recent average on the pair in both back-tests
     * @param errorShare its miss over both back-tests as a share of units; NaN with too few units to say
     * @param naiveShare the same for the recent average
     * @param nextUnits the weekly units it expects over the next four weeks
     */
    record Verdict(String method, boolean usable, boolean proven, double errorShare, double naiveShare,
            double nextUnits, String note) {
    }

    /**
     * What a pair's forecast is. With {@link #AUTO}, the forecaster that proved itself on the pair, and nothing
     * where none did. With one model named, that model's forecast wherever it has one: the person chose it, so
     * it is used whether or not it proved itself here, and the note says which.
     */
    static Verdict verdict(Map<String, MethodEval> methods, String model) {
        if (model != null && !AUTO.equals(model)) {
            String label = Smoothers.label(model);
            MethodEval ev = methods.get(model);
            if (ev == null || !Double.isFinite(ev.nextUnits())) {
                return new Verdict(model, false, false, Double.NaN, Double.NaN, Double.NaN, "Not used: " + label
                        + " is the model chosen in Settings, and it has no forecast for this item from the last "
                        + "training run.");
            }
            Score both = bothOf(ev);
            boolean proven = ev.earlier().beats(MIN_HOLDOUT_UNITS) && ev.later().beats(MIN_HOLDOUT_UNITS);
            return new Verdict(model, true, proven, shareOrNaN(both, both.errorShare()),
                    shareOrNaN(both, both.naiveShare()), ev.nextUnits(), label + " forecasts this item: it is the "
                            + "model chosen in Settings. " + (proven
                                    ? "It beat the recent average here in both back-tests."
                                    : "It has not beaten the recent average here in both back-tests."));
        }
        Pick pick = pick(methods);
        String shown = pick.proven() != null ? pick.proven() : pick.closest() != null ? pick.closest() : Smoothers.FOREST;
        MethodEval ev = methods.get(shown);
        if (ev == null) {
            return new Verdict(shown, false, false, Double.NaN, Double.NaN, Double.NaN,
                    "Not used: too few sales in one of the two back-tests to judge a forecast here.");
        }
        Score both = bothOf(ev);
        boolean scored = ev.earlier().enough(MIN_HOLDOUT_UNITS) && ev.later().enough(MIN_HOLDOUT_UNITS);
        String note = pick.proven() != null
                ? Smoothers.label(shown) + " forecasts this item: it beat the recent average here in both back-tests."
                : scored
                        ? "Not used: no method beat the recent average here in both back-tests; the closest was "
                                + Smoothers.label(shown) + "."
                        : "Not used: too few sales in one of the two back-tests to judge a forecast here.";
        return new Verdict(shown, pick.proven() != null, pick.proven() != null, shareOrNaN(both, both.errorShare()),
                shareOrNaN(both, both.naiveShare()), ev.nextUnits(), note);
    }

    private static double shareOrNaN(Score both, double share) {
        return both.enough(MIN_HOLDOUT_UNITS) ? share : Double.NaN;
    }

    /**
     * @param proven the forecaster to use: it beat the recent average on the pair in both back-tests; null when
     *        none did
     * @param closest the forecaster that missed least over both back-tests, proven or not; null when none was scored
     */
    record Pick(String proven, String closest) {
    }

    /**
     * Which forecaster a pair may use. A forecaster is proven when it beat the recent average by the margin in
     * the earlier back-test and again in the later one, each on enough units to mean something; of several
     * proven, the one that missed least over the two. The first in the map wins a tie.
     */
    static Pick pick(Map<String, MethodEval> methods) {
        String proven = null;
        String closest = null;
        for (Map.Entry<String, MethodEval> m : methods.entrySet()) {
            MethodEval ev = m.getValue();
            Score both = bothOf(ev);
            if (both.units() <= 0 || !Double.isFinite(ev.nextUnits())) {
                continue;
            }
            if (closest == null || both.errorShare() < bothOf(methods.get(closest)).errorShare()) {
                closest = m.getKey();
            }
            boolean twice = ev.earlier().beats(MIN_HOLDOUT_UNITS) && ev.later().beats(MIN_HOLDOUT_UNITS);
            if (twice && (proven == null || both.errorShare() < bothOf(methods.get(proven)).errorShare())) {
                proven = m.getKey();
            }
        }
        return new Pick(proven, closest);
    }

    private static Score bothOf(MethodEval ev) {
        return ev.earlier().plus(ev.later());
    }

    static Model<Regressor> fit(List<WeeklyGrid.Row> rows, Settings s, boolean season, boolean withLags) {
        return fit(rows, s, season, withLags, FOREST);
    }

    static Model<Regressor> fit(List<WeeklyGrid.Row> rows, Settings s, boolean season, boolean withLags,
            Learner learner) {
        MutableDataset<Regressor> ds = new MutableDataset<>(new SimpleDataSourceProvenance("aatlas-demand", FACTORY),
                FACTORY);
        for (WeeklyGrid.Row r : rows) {
            Features.Vector v = Features.of(Features.at(r, s.itemOneHot(), season, withLags));
            ds.add(new ArrayExample<>(new Regressor(Features.TARGET, Features.target(r.units())), v.names(),
                    v.values()));
        }
        return learner.trainer(s).train(ds);
    }

    /**
     * The mean weekly units over the weeks in {@code prices}, the forecast model rolled forward: each week's
     * forecast becomes the next week's most recent lag, so nothing after the start is seen.
     */
    static double rollForward(Model<Regressor> forecast, WeeklyGrid.Context pair, double[] startLags, double compMedian,
            double[] prices, LocalDate firstWeek, double calibration, boolean itemOneHot, boolean season) {
        double[] lags = startLags == null ? new double[WeeklyGrid.LAGS] : startLags.clone();
        double sum = 0;
        double prev = prices[0];
        for (int k = 0; k < prices.length; k++) {
            double units = calibration * predictUnits(forecast, new Features.Input(prices[k], prev, compMedian, lags,
                    pair.medianPrice(), firstWeek.plusWeeks(k), pair.category(), pair.store(), pair.item(), itemOneHot,
                    season, true));
            sum += units;
            System.arraycopy(lags, 0, lags, 1, lags.length - 1);
            lags[0] = units;
            prev = prices[k];
        }
        return sum / prices.length;
    }

    /**
     * Per pair, actual over predicted units on the rows given (one week ahead, real lags): the scale that puts
     * the model's level back on the pair's own mean. Kept within [{@value #CALIBRATION_MIN},
     * {@value #CALIBRATION_MAX}]; a pair the model predicts nothing for keeps 1.
     */
    static Map<String, Double> calibration(Model<Regressor> model, List<WeeklyGrid.Row> rows, boolean itemOneHot,
            boolean season) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (WeeklyGrid.Row r : rows) {
            double[] a = sums.computeIfAbsent(r.pair(), p -> new double[2]);
            a[0] += r.units();
            a[1] += predictUnits(model, Features.at(r, itemOneHot, season, true));
        }
        Map<String, Double> scale = new LinkedHashMap<>();
        sums.forEach((pair, a) -> scale.put(pair, a[1] > 0.5 && a[0] > 0
                ? Math.max(CALIBRATION_MIN, Math.min(CALIBRATION_MAX, a[0] / a[1])) : 1.0));
        return scale;
    }

    /**
     * A forecaster's four-week miss over a pair's rows {@code from} to {@code to}, in blocks of
     * {@value #HORIZON_WEEKS}: {@code forecastAt} answers the weekly units expected over the block that starts
     * at a row, having seen nothing from that row on. The baseline is the eight weeks before the block, held
     * flat. A block with fewer than {@value #MIN_HISTORY_WEEKS} weeks of the pair before it is not scored.
     */
    static Score scoreBlocks(List<WeeklyGrid.Row> rows, int from, int to, IntToDoubleFunction forecastAt) {
        double actual = 0;
        double error = 0;
        double naive = 0;
        for (int start = from; start + HORIZON_WEEKS <= to; start += HORIZON_WEEKS) {
            if (start < MIN_HISTORY_WEEKS) {
                continue;
            }
            double sold = 0;
            for (int k = 0; k < HORIZON_WEEKS; k++) {
                sold += rows.get(start + k).units();
            }
            double actualMean = sold / HORIZON_WEEKS;
            actual += actualMean;
            error += Math.abs(forecastAt.applyAsDouble(start) - actualMean);
            naive += Math.abs(mean(rows.get(start).lags()) - actualMean);
        }
        return new Score(actual, error, naive);
    }

    /** The prices actually charged over the block that starts at {@code from}, which a seller knows in advance. */
    private static double[] prices(List<WeeklyGrid.Row> rows, int from) {
        double[] prices = new double[HORIZON_WEEKS];
        for (int k = 0; k < HORIZON_WEEKS; k++) {
            prices[k] = rows.get(from + k).price();
        }
        return prices;
    }

    /** Units for the inputs, from a model. */
    static double predictUnits(Model<Regressor> model, Features.Input in) {
        Features.Vector v = Features.of(in);
        Prediction<Regressor> p = model.predict(new ArrayExample<>(UNKNOWN, v.names(), v.values()));
        return Features.units(p.getOutput().getValues()[0]);
    }

    /** The pair's settled weekly units at {@code price}, from the response model. */
    static double settledUnits(Model<Regressor> response, WeeklyGrid.Context c, double price, boolean itemOneHot,
            boolean season) {
        return predictUnits(response, Features.at(c, price, itemOneHot, season, false));
    }

    /** The log-log slope of the pair's settled demand across the probe grid around its last price. */
    static double elasticity(Model<Regressor> response, WeeklyGrid.Context c, boolean itemOneHot, boolean season) {
        if (c.lastPrice() <= 0) {
            return 0;
        }
        double[] xs = new double[PROBE.length];
        double[] ys = new double[PROBE.length];
        for (int i = 0; i < PROBE.length; i++) {
            double price = c.lastPrice() * PROBE[i];
            xs[i] = Math.log(price);
            ys[i] = Math.log(Math.max(0.05, settledUnits(response, c, price, itemOneHot, season)));
        }
        double slope = slope(xs, ys);
        return Math.max(ELASTICITY_MIN, Math.min(ELASTICITY_MAX, slope));
    }

    static double slope(double[] xs, double[] ys) {
        int n = xs.length;
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += xs[i];
            my += ys[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (xs[i] - mx) * (ys[i] - my);
            sxx += (xs[i] - mx) * (xs[i] - mx);
        }
        return sxx == 0 ? 0 : sxy / sxx;
    }

    static double mean(double[] xs) {
        if (xs == null || xs.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double x : xs) {
            sum += Math.max(0, x);
        }
        return sum / xs.length;
    }
}

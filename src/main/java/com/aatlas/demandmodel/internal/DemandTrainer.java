package com.aatlas.demandmodel.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Prediction;
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
 * the profit-max step needs. The forecast model is fitted twice: once without the held-out
 * weeks for the score, once on everything to serve.
 *
 * <p>The models are random forests of regression trees (Tribuo): no native binaries, seconds
 * to train at thousands of rows, and at ease with one-hot items beside continuous prices. Price
 * sensitivity is read out rather than assumed: the pair's settled demand is predicted at seven
 * prices from 15% under to 15% over its last price, and the log-log slope through those
 * predictions is the elasticity. A pair is usable when the forecast model beat the baseline on
 * its held-out weeks by a margin on at least {@value #MIN_HOLDOUT_UNITS} units and the slope is
 * negative; a slope that says units rise with price is reported but never used.
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

    /** One pair's score. Errors are shares of the units sold over the held-out weeks (0.2 = 20%). */
    record PairEval(int weeks, double holdoutUnits, double errorModel, double errorBaseline, boolean beats,
            boolean usable, double elasticity, double forecastNext, double settledNext, String note) {
    }

    /**
     * @param forecast the model that sees the lags: next week's units
     * @param response the model that does not: settled units at a price, and the elasticity
     */
    record Result(Model<Regressor> forecast, Model<Regressor> response, Map<String, PairEval> evals, int rows,
            int trainRows, long millis, boolean season) {
    }

    /** Whether a model was fitted with the season features: serving must build the same features. */
    static boolean trainedWithSeason(Model<Regressor> model) {
        return model.getFeatureIDMap().get(Features.SEASON_SIN) != null;
    }

    static Result train(WeeklyGrid.Grid grid, Settings s) {
        long t0 = System.nanoTime();
        boolean season = grid.spanWeeks() >= SEASON_MIN_WEEKS;
        // The upload's first week is partial unless it began on a Monday: never a week to learn from.
        List<WeeklyGrid.Row> rows = grid.rows().stream().filter(r -> !r.week().equals(grid.firstWeek())).toList();
        if (rows.size() < MIN_ROWS) {
            throw new IllegalStateException("only " + rows.size() + " weekly rows; " + MIN_ROWS + " needed");
        }
        LocalDate cutoff = grid.lastWeek().minusWeeks(s.holdoutWeeks());
        List<WeeklyGrid.Row> train = new ArrayList<>();
        Map<String, List<WeeklyGrid.Row>> holdout = new LinkedHashMap<>();
        for (WeeklyGrid.Row r : rows) {
            if (r.week().isAfter(cutoff)) {
                holdout.computeIfAbsent(r.pair(), k -> new ArrayList<>()).add(r);
            } else {
                train.add(r);
            }
        }
        if (train.size() < MIN_ROWS) {
            throw new IllegalStateException("only " + train.size() + " rows before the held-out weeks; " + MIN_ROWS
                    + " needed");
        }
        Model<Regressor> scorer = fit(train, s, season, true);
        Model<Regressor> forecast = fit(rows, s, season, true);
        Model<Regressor> response = fit(rows, s, season, false);

        Map<String, PairEval> evals = new LinkedHashMap<>();
        for (Map.Entry<String, WeeklyGrid.Context> e : grid.contexts().entrySet()) {
            WeeklyGrid.Context c = e.getValue();
            double actual = 0;
            double errModel = 0;
            double errBase = 0;
            for (WeeklyGrid.Row r : holdout.getOrDefault(e.getKey(), List.of())) {
                double m = predictUnits(scorer, Features.at(r, s.itemOneHot(), season, true));
                double b = mean(r.lags());
                actual += r.units();
                errModel += Math.abs(m - r.units());
                errBase += Math.abs(b - r.units());
            }
            double elasticity = elasticity(response, c, s.itemOneHot(), season);
            double next = predictUnits(forecast, Features.at(c, c.lastPrice(), s.itemOneHot(), season, true));
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
            evals.put(e.getKey(), new PairEval(c.weeks(), actual, em, eb, beats, usable, elasticity, next, settled,
                    note));
        }
        return new Result(forecast, response, evals, rows.size(), train.size(), (System.nanoTime() - t0) / 1_000_000,
                season);
    }

    static Model<Regressor> fit(List<WeeklyGrid.Row> rows, Settings s, boolean season, boolean withLags) {
        MutableDataset<Regressor> ds = new MutableDataset<>(new SimpleDataSourceProvenance("aatlas-demand", FACTORY),
                FACTORY);
        for (WeeklyGrid.Row r : rows) {
            Features.Vector v = Features.of(Features.at(r, s.itemOneHot(), season, withLags));
            ds.add(new ArrayExample<>(new Regressor(Features.TARGET, Features.target(r.units())), v.names(),
                    v.values()));
        }
        CARTRegressionTrainer tree = new CARTRegressionTrainer(s.maxDepth(), (float) s.minChildWeight(), 0f,
                (float) s.featureFraction(), false, new MeanSquaredError(), s.seed());
        RandomForestTrainer<Regressor> forest = new RandomForestTrainer<>(tree, new AveragingCombiner(), s.trees(),
                s.seed());
        return forest.train(ds);
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

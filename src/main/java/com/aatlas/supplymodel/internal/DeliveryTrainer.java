package com.aatlas.supplymodel.internal;

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
 * Fits the delivery models and scores them honestly.
 *
 * <p>Two forests on the received orders in date order: the <em>slip</em> model predicts the
 * days an order beats or misses its promise; the <em>late</em> model predicts late as 0 or 1,
 * which the forest's averaging turns into a probability. The latest {@code holdoutShare} of
 * the orders is held back; both models are fitted on the rest, predict the held-back orders,
 * and are scored per supplier against a naive baseline that knew the same record: the
 * supplier's trailing mean slip for the days, its trailing late rate for the chance. Then
 * both are refitted on everything to serve.
 *
 * <p>A supplier is usable when the model beat the baseline on at least one of the two, by a
 * margin, over at least {@value #MIN_HOLDOUT} held-out orders. Pure: a grid in, models and a
 * report out.
 */
final class DeliveryTrainer {

    /** Rows before the forests are worth fitting at all. */
    static final int MIN_ROWS = 30;
    /** Held-out orders a supplier needs before its score means anything. */
    static final int MIN_HOLDOUT = 3;
    /** Days under the baseline's error the model must be, to count as a win on lead time. */
    static final double LEAD_MARGIN = 0.25;
    /** Brier points under the baseline the model must be, to count as a win on lateness. */
    static final double LATE_MARGIN = 0.02;
    /** Days of history before the season features are trusted. */
    static final int SEASON_MIN_DAYS = 540;

    private static final RegressionFactory FACTORY = new RegressionFactory();
    private static final Regressor UNKNOWN = FACTORY.getUnknownOutput();

    private DeliveryTrainer() {
    }

    record Settings(int trees, int maxDepth, int minChildWeight, double featureFraction, double holdoutShare,
            int minHoldout, long seed) {

        static Settings defaults() {
            return new Settings(100, 8, 3, 0.6, 0.2, 8, 42L);
        }

        Settings withTrees(int n) {
            return new Settings(n, maxDepth, minChildWeight, featureFraction, holdoutShare, minHoldout, seed);
        }
    }

    /** One supplier's score. Errors in days; Brier scores on late-or-not. NaN when there was too little to score. */
    record SupplierEval(int orders, int holdoutOrders, double maeModel, double maeBaseline, double brierModel,
            double brierBaseline, boolean beatsLead, boolean beatsLate, boolean usable, double predictedSlip,
            double predictedLateProb, String note) {
    }

    record Result(Model<Regressor> slip, Model<Regressor> late, Map<String, SupplierEval> evals, int rows,
            int trainRows, int holdoutRows, long millis, boolean season) {
    }

    static boolean trainedWithSeason(Model<Regressor> model) {
        return model.getFeatureIDMap().get(DeliveryFeatures.SEASON_SIN) != null;
    }

    static Result train(OrderGrid.Grid grid, Settings s) {
        long t0 = System.nanoTime();
        List<OrderGrid.Row> rows = grid.rows();
        if (rows.size() < MIN_ROWS) {
            throw new IllegalStateException("only " + rows.size() + " received orders; " + MIN_ROWS + " needed");
        }
        boolean season = grid.firstOrder() != null && grid.lastOrder() != null
                && java.time.temporal.ChronoUnit.DAYS.between(grid.firstOrder(), grid.lastOrder()) >= SEASON_MIN_DAYS;
        int holdoutN = Math.max(s.minHoldout(), (int) Math.round(rows.size() * s.holdoutShare()));
        int cut = rows.size() - holdoutN;
        if (cut < MIN_ROWS / 2) {
            throw new IllegalStateException("only " + cut + " orders before the held-out ones; " + (MIN_ROWS / 2)
                    + " needed");
        }
        List<OrderGrid.Row> train = rows.subList(0, cut);
        List<OrderGrid.Row> holdout = rows.subList(cut, rows.size());
        double trainLateRate = train.stream().filter(r -> r.order().late()).count() / (double) train.size();

        Model<Regressor> slipScorer = fit(train, s, season, true);
        Model<Regressor> lateScorer = fit(train, s, season, false);

        Map<String, double[]> acc = new LinkedHashMap<>(); // n, |slip err| model, baseline, brier model, baseline
        for (OrderGrid.Row r : holdout) {
            DeliveryFeatures.Input in = DeliveryFeatures.at(r, season);
            double slipPred = predict(slipScorer, in);
            double latePred = clamp01(predict(lateScorer, in));
            double slipBase = r.hasPrev() ? r.prevMeanSlip() : 0;
            double lateBase = r.hasPrev() ? r.prevLateRate() : trainLateRate;
            double y = r.order().late() ? 1 : 0;
            double[] a = acc.computeIfAbsent(r.order().supplierId(), k -> new double[5]);
            a[0]++;
            a[1] += Math.abs(slipPred - r.order().slip());
            a[2] += Math.abs(slipBase - r.order().slip());
            a[3] += (latePred - y) * (latePred - y);
            a[4] += (lateBase - y) * (lateBase - y);
        }

        Model<Regressor> slip = fit(rows, s, season, true);
        Model<Regressor> late = fit(rows, s, season, false);

        Map<String, SupplierEval> evals = new LinkedHashMap<>();
        for (Map.Entry<String, OrderGrid.Context> e : grid.contexts().entrySet()) {
            OrderGrid.Context c = e.getValue();
            double[] a = acc.getOrDefault(e.getKey(), new double[5]);
            int n = (int) a[0];
            boolean enough = n >= MIN_HOLDOUT;
            double maeM = enough ? a[1] / n : Double.NaN;
            double maeB = enough ? a[2] / n : Double.NaN;
            double brM = enough ? a[3] / n : Double.NaN;
            double brB = enough ? a[4] / n : Double.NaN;
            boolean beatsLead = enough && maeM < maeB - LEAD_MARGIN;
            boolean beatsLate = enough && brM < brB - LATE_MARGIN;
            boolean usable = beatsLead || beatsLate;
            DeliveryFeatures.Input typical = DeliveryFeatures.at(c, null, null, c.meanQty(), c.meanPromised(),
                    c.lastOrder(), season);
            double predictedSlip = predict(slip, typical);
            double predictedLate = clamp01(predict(late, typical));
            String note = !enough ? "too few held-out orders to score"
                    : beatsLead && beatsLate ? "beat the supplier's own record on lead time and on lateness"
                    : beatsLead ? "beat the supplier's own record on lead time"
                    : beatsLate ? "beat the supplier's own record on lateness"
                    : "did not beat the supplier's own trailing record on the held-out orders";
            evals.put(e.getKey(), new SupplierEval(c.orders(), n, maeM, maeB, brM, brB, beatsLead, beatsLate, usable,
                    predictedSlip, predictedLate, note));
        }
        return new Result(slip, late, evals, rows.size(), train.size(), holdout.size(),
                (System.nanoTime() - t0) / 1_000_000, season);
    }

    static Model<Regressor> fit(List<OrderGrid.Row> rows, Settings s, boolean season, boolean slipTarget) {
        MutableDataset<Regressor> ds = new MutableDataset<>(new SimpleDataSourceProvenance("aatlas-delivery", FACTORY),
                FACTORY);
        for (OrderGrid.Row r : rows) {
            DeliveryFeatures.Vector v = DeliveryFeatures.of(DeliveryFeatures.at(r, season));
            double target = slipTarget ? r.order().slip() : (r.order().late() ? 1 : 0);
            ds.add(new ArrayExample<>(new Regressor(slipTarget ? DeliveryFeatures.SLIP : DeliveryFeatures.LATE, target),
                    v.names(), v.values()));
        }
        CARTRegressionTrainer tree = new CARTRegressionTrainer(s.maxDepth(), (float) s.minChildWeight(), 0f,
                (float) s.featureFraction(), false, new MeanSquaredError(), s.seed());
        RandomForestTrainer<Regressor> forest = new RandomForestTrainer<>(tree, new AveragingCombiner(), s.trees(),
                s.seed());
        return forest.train(ds);
    }

    static double predict(Model<Regressor> model, DeliveryFeatures.Input in) {
        DeliveryFeatures.Vector v = DeliveryFeatures.of(in);
        Prediction<Regressor> p = model.predict(new ArrayExample<>(UNKNOWN, v.names(), v.values()));
        return p.getOutput().getValues()[0];
    }

    static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    static List<OrderGrid.Row> copy(List<OrderGrid.Row> rows) {
        return new ArrayList<>(rows);
    }
}

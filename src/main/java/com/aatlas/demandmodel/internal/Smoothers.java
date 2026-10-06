package com.aatlas.demandmodel.internal;

import java.util.Arrays;
import java.util.List;

/**
 * The classical forecasters that stand beside the forest: simple exponential smoothing, for items that
 * sell most weeks, and two Croston-type methods built for items that sell in lumps with empty weeks
 * between. SBA smooths the size of a sale and the gap between sales and corrects Croston's upward bias;
 * TSB smooths the size of a sale and the chance of one in a week, so an item that has stopped selling
 * fades instead of holding its old level.
 *
 * <p>Each reads only the pair's own weekly units and answers with one level: the units a week it expects
 * from here on. A series is oldest week first. {@code levels[t]} is the forecast made having seen the
 * first {@code t} weeks and nothing after, so a back-test reads the level at the week its window starts.
 * The first {@value #WARM_UP} weeks start every method from the plain running mean.
 *
 * <p>Pure: a series in, levels out.
 */
final class Smoothers {

    static final String FOREST = "forest";
    static final String SES = "ses";
    static final String SBA = "sba";
    static final String TSB = "tsb";
    /** The pretrained model that answers from its own service; it is not a smoother, but it is named here with the rest. */
    static final String CHRONOS = "chronos";

    /** Weeks a method starts its level from before it begins to smooth. */
    static final int WARM_UP = 8;

    /**
     * @param grid the smoothing settings tried for a pair; the one that would have forecast its own earlier
     *        weeks best is used
     */
    record Method(String key, String label, double[][] grid) {
    }

    static final List<Method> METHODS = List.of(
            new Method(SES, "Aatlas Pulse 1.0", new double[][] {{0.05}, {0.1}, {0.2}, {0.3}}),
            new Method(SBA, "Aatlas Burst 1.2", new double[][] {{0.05}, {0.1}, {0.2}}),
            new Method(TSB, "Aatlas Burst 1.3",
                    new double[][] {{0.1, 0.05}, {0.1, 0.1}, {0.1, 0.2}, {0.2, 0.05}, {0.2, 0.1}, {0.2, 0.2}}));

    private Smoothers() {
    }

    /**
     * The name a person reads, in Aatlas's own model names: Pulse (exponential smoothing), Burst 1.2 and 1.3
     * (Croston SBA and TSB), Market (the random forest), Deep (the pretrained Chronos forecaster). The forest
     * included. An unknown or missing key reads as the forest. */
    static String label(String key) {
        for (Method m : METHODS) {
            if (m.key().equals(key)) {
                return m.label();
            }
        }
        return CHRONOS.equals(key) ? "Aatlas Deep 2.0" : "Aatlas Market 1.5";
    }

    /**
     * The level after each week: {@code levels[t]} has seen {@code y[0..t)}. The result has one more entry
     * than the series; its last is the forecast from the end.
     */
    static double[] levels(String method, double[] y, double[] params) {
        int n = y.length;
        double[] out = new double[n + 1];
        int warm = Math.min(WARM_UP, n);
        double sum = 0;
        double sizeSum = 0;
        int sales = 0;
        int sinceSale = 1;
        for (int t = 0; t < warm; t++) {
            sum += y[t];
            if (y[t] > 0) {
                sizeSum += y[t];
                sales++;
                sinceSale = 1;
            } else {
                sinceSale++;
            }
            out[t + 1] = sum / (t + 1);
        }
        if (warm == n) {
            return out;
        }
        double alpha = params[0];
        double level = out[warm];
        double size = sales > 0 ? sizeSum / sales : 0;
        double gap = sales > 0 ? (double) warm / sales : warm;
        double chance = (double) sales / warm;
        for (int t = warm; t < n; t++) {
            boolean sold = y[t] > 0;
            switch (method) {
                case SBA -> {
                    if (sold) {
                        size = size > 0 ? size + alpha * (y[t] - size) : y[t];
                        gap += alpha * (sinceSale - gap);
                    }
                    level = size > 0 ? (1 - alpha / 2) * size / Math.max(1, gap) : 0;
                }
                case TSB -> {
                    double beta = params[1];
                    if (sold) {
                        size = size > 0 ? size + alpha * (y[t] - size) : y[t];
                    }
                    chance += beta * ((sold ? 1 : 0) - chance);
                    level = chance * size;
                }
                default -> level += alpha * (y[t] - level);
            }
            sinceSale = sold ? 1 : sinceSale + 1;
            out[t + 1] = level;
        }
        return out;
    }

    /**
     * The settings from the method's grid that would have forecast the first {@code n} weeks best: the
     * smallest total miss of the level against the mean of the {@code horizon} weeks that followed, over
     * every week after the warm-up that has a full horizon inside the {@code n}. Nothing from week
     * {@code n} on is read. With too few weeks to tell, the first settings.
     */
    static double[] best(Method method, double[] y, int n, int horizon) {
        double[] seen = n >= y.length ? y : Arrays.copyOf(y, n);
        double[] chosen = method.grid()[0];
        double least = Double.MAX_VALUE;
        for (double[] params : method.grid()) {
            double[] levels = levels(method.key(), seen, params);
            double miss = 0;
            int scored = 0;
            for (int t = WARM_UP; t + horizon <= seen.length; t++) {
                double actual = 0;
                for (int k = 0; k < horizon; k++) {
                    actual += seen[t + k];
                }
                miss += Math.abs(levels[t] - actual / horizon);
                scored++;
            }
            if (scored > 0 && miss < least) {
                least = miss;
                chosen = params;
            }
        }
        return chosen;
    }
}

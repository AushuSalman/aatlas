package com.aatlas.history;

import java.util.Arrays;

/**
 * Pure statistics over primitives: the log-log regression behind elasticity, the linear
 * interpolation quantiles behind bands and residual spreads, weighted means and the
 * seasonal index. No SQL, no clock, no boxing; "unavailable" is a flag on the record, never
 * a NaN a caller has to remember to test.
 */
public final class Stats {

    /** Below this a sum of squares is treated as zero: a list-priced or constant-volume item. */
    public static final double VARIANCE_GUARD = 1e-9;

    /** Fewest points a regression is reported on: a slope, an intercept and two degrees of freedom. */
    public static final int MIN_OLS_POINTS = 4;

    /** Not-a-number without touching {@code java.lang.Double}; test with {@code x != x}. */
    public static final double NAN = 0.0 / 0.0;

    private Stats() {
    }

    /**
     * An ordinary least squares fit.
     *
     * @param coefficient the slope
     * @param r2 coefficient of determination, 0-1
     * @param stdError standard error of the slope
     * @param n points used
     * @param available false when the guards rejected the fit (too few points, no variance)
     */
    public record Ols(double coefficient, double intercept, double r2, double stdError, int n, boolean available) {

        public static Ols unavailable(int n) {
            return new Ols(0, 0, 0, 0, n, false);
        }
    }

    /** {@code ln(units)} on {@code ln(price)}; non-positive inputs are dropped before the fit. */
    public static Ols olsLogLog(double[] prices, double[] units) {
        int n = Math.min(prices.length, units.length);
        double[] x = new double[n];
        double[] y = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (prices[i] > 0 && units[i] > 0) {
                x[k] = Math.log(prices[i]);
                y[k] = Math.log(units[i]);
                k++;
            }
        }
        return ols(Arrays.copyOf(x, k), Arrays.copyOf(y, k));
    }

    /** Plain least squares of {@code y} on {@code x} with the Sxx/Syy guards. */
    public static Ols ols(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < MIN_OLS_POINTS) {
            return Ols.unavailable(n);
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        return fromSums(sxx, syy, sxy, n, my - (sxy / sxx) * mx);
    }

    /**
     * Pooled within-group regression: every group's x and y are demeaned against that
     * group's own mean before one slope is fitted across all of them. This is the category
     * elasticity rung: each item contributes its own price/volume co-movement and none of
     * the level differences between items.
     */
    public static Ols pooledDemeaned(double[][] x, double[][] y) {
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        int n = 0;
        for (int g = 0; g < Math.min(x.length, y.length); g++) {
            int m = Math.min(x[g].length, y[g].length);
            if (m < 2) {
                continue;
            }
            double mx = 0;
            double my = 0;
            for (int i = 0; i < m; i++) {
                mx += x[g][i];
                my += y[g][i];
            }
            mx /= m;
            my /= m;
            for (int i = 0; i < m; i++) {
                double dx = x[g][i] - mx;
                double dy = y[g][i] - my;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
            n += m;
        }
        if (n < MIN_OLS_POINTS) {
            return Ols.unavailable(n);
        }
        return fromSums(sxx, syy, sxy, n, 0);
    }

    private static Ols fromSums(double sxx, double syy, double sxy, int n, double intercept) {
        if (sxx < VARIANCE_GUARD || syy < VARIANCE_GUARD) {
            return Ols.unavailable(n);
        }
        double beta = sxy / sxx;
        double r2 = (sxy * sxy) / (sxx * syy);
        double residual = Math.max(0, syy - beta * sxy);
        double se = n > 2 ? Math.sqrt(residual / (n - 2) / sxx) : 0;
        return new Ols(beta, intercept, r2, se, n, true);
    }

    /** {@code [q1, median, q3]} by linear interpolation (the R type-7 definition, PostgreSQL's percentile_cont). */
    public static double[] quantiles(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return new double[] {quantileSorted(sorted, 0.25), quantileSorted(sorted, 0.5), quantileSorted(sorted, 0.75)};
    }

    /** One quantile, {@code p} in 0-1, by linear interpolation. NaN for an empty array. */
    public static double quantile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return quantileSorted(sorted, p);
    }

    private static double quantileSorted(double[] sorted, double p) {
        if (sorted.length == 0) {
            return NAN;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, sorted.length - 1);
        double fraction = position - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    /** Weighted mean; NaN when the weights sum to zero. */
    public static double weightedAvg(double[] values, double[] weights) {
        double sum = 0;
        double weight = 0;
        for (int i = 0; i < Math.min(values.length, weights.length); i++) {
            sum += values[i] * weights[i];
            weight += weights[i];
        }
        return weight == 0 ? NAN : sum / weight;
    }

    public static double mean(double[] values, int from, int to) {
        if (to <= from) {
            return NAN;
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += values[i];
        }
        return sum / (to - from);
    }

    /** Population variance. */
    public static double variance(double[] values, int from, int to) {
        if (to - from < 1) {
            return 0;
        }
        double mean = mean(values, from, to);
        double sum = 0;
        for (int i = from; i < to; i++) {
            double d = values[i] - mean;
            sum += d * d;
        }
        return sum / (to - from);
    }

    /** Sample standard deviation; 0 with fewer than two points. */
    public static double sampleSd(double[] values, int from, int to) {
        int n = to - from;
        if (n < 2) {
            return 0;
        }
        double mean = mean(values, from, to);
        double sum = 0;
        for (int i = from; i < to; i++) {
            double d = values[i] - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (n - 1));
    }

    /** Fewest distinct months with sales, and the span they must cover, for a seasonal index. */
    public static final int SEASONALITY_MIN_MONTHS = 18;

    /**
     * Twelve seasonal indices, January first, mean exactly 1.
     *
     * <p>{@code index[m] = mean(units in calendar month m) / mean(all months with data)},
     * months with no data taking a neutral 1, each clamped to [0.3, 3] and the twelve then
     * normalised to mean 1. Null when fewer than eighteen distinct months carry sales or the
     * first and last of them span fewer than eighteen months.
     *
     * @param units one value per consecutive calendar month, oldest first
     * @param firstMonth the calendar month (1-12) of {@code units[0]}
     */
    public static double[] seasonalIndices(double[] units, int firstMonth) {
        int withData = 0;
        int first = -1;
        int last = -1;
        double total = 0;
        for (int i = 0; i < units.length; i++) {
            if (units[i] > 0) {
                withData++;
                total += units[i];
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (withData < SEASONALITY_MIN_MONTHS || last - first + 1 < SEASONALITY_MIN_MONTHS) {
            return null;
        }
        double overall = total / withData;
        double[] sums = new double[12];
        int[] counts = new int[12];
        for (int i = 0; i < units.length; i++) {
            if (units[i] > 0) {
                int month = Math.floorMod(firstMonth - 1 + i, 12);
                sums[month] += units[i];
                counts[month]++;
            }
        }
        double[] index = new double[12];
        double sum = 0;
        for (int m = 0; m < 12; m++) {
            double raw = counts[m] == 0 ? 1 : (sums[m] / counts[m]) / overall;
            index[m] = Math.max(0.3, Math.min(3, raw));
            sum += index[m];
        }
        double mean = sum / 12;
        for (int m = 0; m < 12; m++) {
            index[m] = index[m] / mean;
        }
        return index;
    }

    public static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}

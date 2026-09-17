package com.aatlas.history;

import java.util.ArrayList;
import java.util.List;

/**
 * Seasonal naive forecast with a damped log-linear trend (spec 3.4), over a monthly unit
 * series. Pure: the series, its calendar alignment and an optional seasonal index go in;
 * points with a p10/p50/p90 band per horizon come out. The caller decides what the series
 * is (an item at a branch, or a category scaled to the item for a cold start).
 */
public final class Forecasts {

    /** Fewer months than this since the first sale and the item is a cold start. */
    public static final int COLD_START_MONTHS = 6;

    /** Fewest usable residuals before the spread falls back to the fixed 0.75/1.30 ratios. */
    public static final int MIN_RESIDUALS = 6;

    public static final double FALLBACK_Q10 = 0.75;
    public static final double FALLBACK_Q90 = 1.30;
    public static final double MAX_MONTHLY_GROWTH = 0.08;
    public static final double WEEKS_PER_MONTH = 4.35;
    public static final double DAYS_PER_MONTH = 30.4;
    public static final double SENSING_SPREAD = 1.35;
    public static final double STRATEGIC_DAMPING = 0.85;

    private Forecasts() {
    }

    public record Point(String label, double p10, double p50, double p90) {
    }

    /**
     * Backtest accuracy: mean absolute percentage error of the model and of the naive
     * forecast over the last origins. {@code available} is false with fewer than three
     * origins; the UI then hides the panel.
     */
    public record Accuracy(double naiveMape, double modelMape, int origins, boolean available) {
    }

    /** Share of the variation each driver explains, summing to 100. */
    public record Weights(double seasonality, double trend, double ownPrice, double unexplained) {
    }

    /**
     * The forecast.
     *
     * @param coldStart fewer than six months since the first sale in the series given
     * @param level the deseasonalised level at the origin (the last month)
     * @param growth monthly growth, −0.08..0.08
     * @param q10 residual ratio at the 10th percentile
     * @param q90 residual ratio at the 90th percentile
     * @param months months since the first sale
     */
    public record Forecast(boolean coldStart, double level, double growth, double q10, double q90, int months,
            boolean seasonal, boolean intermittent, boolean structuralBreak, List<Point> sensing,
            List<Point> operational, List<Point> tactical, List<Point> strategic, Accuracy accuracy,
            Weights weights) {

        /** {@code ((1+g)^months − 1) × 100}. */
        public double trendPct(int horizonMonths) {
            return (Math.pow(1 + growth, horizonMonths) - 1) * 100;
        }

        /** {@code up} above +2%, {@code down} below −2%, else {@code flat}, over the horizon. */
        public String trend(int horizonMonths) {
            double t = trendPct(horizonMonths);
            return t > 2 ? "up" : t < -2 ? "down" : "flat";
        }

        public Forecast asColdStart() {
            return new Forecast(true, level, growth, q10, q90, months, seasonal, intermittent, structuralBreak,
                    sensing, operational, tactical, strategic, accuracy, weights);
        }
    }

    /**
     * Scales a category series to the item for a cold start: {@code category × itemLast3 /
     * categoryLast3}. Null when the category sold nothing in its last three months or the
     * item has no months at all - the forecast is then locked, not faked.
     */
    public static double[] coldStartSeries(double[] item, double[] category) {
        if (item == null || item.length == 0 || category == null || category.length < 3) {
            return null;
        }
        double itemLast3 = sumLast(item, 3);
        double categoryLast3 = sumLast(category, 3);
        if (categoryLast3 <= 0 || itemLast3 <= 0) {
            return null;
        }
        double factor = itemLast3 / categoryLast3;
        double[] scaled = new double[category.length];
        for (int i = 0; i < category.length; i++) {
            scaled[i] = category[i] * factor;
        }
        return scaled;
    }

    /**
     * The forecast for a monthly unit series.
     *
     * @param series units per consecutive calendar month, oldest first, the last one being
     *     the month of "today"
     * @param endMonth the calendar month (1-12) of the last point
     * @param seasonalIndex twelve indices January first, or null
     * @param beta own-price elasticity used for the "own price" weight
     * @param r2 its r²
     * @param defaultElasticity true when the elasticity is the default (own-price weight 0)
     */
    public static Forecast seasonalNaive(double[] series, int endMonth, double[] seasonalIndex, double beta, double r2,
            boolean defaultElasticity) {
        int t = series.length;
        int firstNonZero = -1;
        for (int i = 0; i < t; i++) {
            if (series[i] > 0) {
                firstNonZero = i;
                break;
            }
        }
        int months = firstNonZero < 0 ? 0 : t - firstNonZero;
        boolean coldStart = months < COLD_START_MONTHS;
        boolean seasonal = seasonalIndex != null && seasonalIndex.length == 12;

        double[] deseasonalised = new double[t];
        for (int i = 0; i < t; i++) {
            deseasonalised[i] = series[i] / indexAt(seasonalIndex, endMonth, i - (t - 1));
        }

        int window = Math.min(months, 12);
        double growth = growth(deseasonalised, t, window);
        double level = level(deseasonalised, t, growth);

        double[] residuals = residuals(series, deseasonalised, seasonalIndex, endMonth, t, months, window, growth);
        double q10;
        double q90;
        if (residuals.length < MIN_RESIDUALS) {
            q10 = FALLBACK_Q10;
            q90 = FALLBACK_Q90;
        } else {
            // A one-sided residual distribution (every month beat its fit) must not invert the
            // band: the low side never rises above p50 and the high side never falls below it.
            q10 = Math.min(1, Stats.quantile(residuals, 0.10));
            q90 = Math.max(1, Stats.quantile(residuals, 0.90));
        }

        List<Point> sensing = new ArrayList<>();
        for (int w = 1; w <= 6; w++) {
            int h = (int) Math.ceil(w / WEEKS_PER_MONTH);
            double p50 = p50(level, growth, h, seasonalIndex, endMonth) * 7 / DAYS_PER_MONTH;
            sensing.add(point("Wk " + w, p50, h, 1, q10, q90, SENSING_SPREAD));
        }
        List<Point> operational = new ArrayList<>();
        for (int h = 1; h <= 6; h++) {
            operational.add(point("Mo " + h, p50(level, growth, h, seasonalIndex, endMonth), h, 1, q10, q90, 1));
        }
        List<Point> tactical = new ArrayList<>();
        for (int b = 1; b <= 6; b++) {
            double sum = 0;
            for (int h = 3 * b - 2; h <= 3 * b; h++) {
                sum += p50(level, growth, h, seasonalIndex, endMonth);
            }
            tactical.add(point("Mo " + (3 * b), sum, 3 * b, 3, q10, q90, 1));
        }
        List<Point> strategic = new ArrayList<>();
        for (int y = 1; y <= 5; y++) {
            double damped = growth * Math.pow(STRATEGIC_DAMPING, y - 1);
            double sum = 0;
            for (int h = 12 * (y - 1) + 1; h <= 12 * y; h++) {
                sum += p50(level, damped, h, seasonalIndex, endMonth);
            }
            strategic.add(point("Yr " + y, sum, 12 * y, 12, q10, q90, 1));
        }

        boolean intermittent = zeroMonths(series, 12) >= 4;
        boolean structuralBreak = structuralBreak(series, months);
        Accuracy accuracy = accuracy(series, deseasonalised, seasonalIndex, endMonth, months);
        Weights weights = weights(series, deseasonalised, seasonal, growth, beta, r2, defaultElasticity);

        return new Forecast(coldStart, level, growth, q10, q90, months, seasonal, intermittent, structuralBreak,
                sensing, operational, tactical, strategic, accuracy, weights);
    }

    // ---- pieces --------------------------------------------------------------------------

    /** The index for the month {@code offset} months after the last point (negative = before). */
    static double indexAt(double[] index, int endMonth, int offset) {
        if (index == null || index.length != 12) {
            return 1;
        }
        int month = Math.floorMod(endMonth - 1 + offset, 12);
        double value = index[month];
        return value > 0 ? value : 1;
    }

    /**
     * Monthly growth from a log-linear fit of {@code ln(units + 1)} on the month index over
     * the last {@code window} months, clamped to ±8%. Zero when the fit has no variance.
     */
    static double growth(double[] values, int end, int window) {
        if (window < 2 || end < window) {
            return 0;
        }
        double[] x = new double[window];
        double[] y = new double[window];
        for (int i = 0; i < window; i++) {
            x[i] = i;
            y[i] = Math.log(values[end - window + i] + 1);
        }
        Stats.Ols fit = Stats.ols(x, y);
        if (!fit.available()) {
            // Two or three points still carry a slope; only a flat line is "no trend".
            if (window >= 2) {
                double slope = (y[window - 1] - y[0]) / (window - 1);
                return Stats.clamp(Math.exp(slope) - 1, -MAX_MONTHLY_GROWTH, MAX_MONTHLY_GROWTH);
            }
            return 0;
        }
        return Stats.clamp(Math.exp(fit.coefficient()) - 1, -MAX_MONTHLY_GROWTH, MAX_MONTHLY_GROWTH);
    }

    /**
     * The level at the origin: the mean of the last three months once each is carried
     * forward to the last month at the fitted growth, so a trending series is not read a
     * month behind.
     */
    static double level(double[] values, int end, double growth) {
        int n = Math.min(3, end);
        if (n == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = end - n; i < end; i++) {
            sum += values[i] * Math.pow(1 + growth, end - 1 - i);
        }
        return sum / n;
    }

    static double p50(double level, double growth, int h, double[] index, int endMonth) {
        return level * Math.pow(1 + growth, h) * indexAt(index, endMonth, h);
    }

    static Point point(String label, double p50, int h, int k, double q10, double q90, double spread) {
        double widen = Math.sqrt(h) / Math.sqrt(k);
        double down = Math.max(0.05, 1 - (1 - q10) * spread * widen);
        double up = 1 + (q90 - 1) * spread * widen;
        return new Point(label, p50 * down, p50, p50 * up);
    }

    /** {@code actual / fitted} over the last window, only where a fitted value exists and is positive. */
    static double[] residuals(double[] series, double[] deseasonalised, double[] index, int endMonth, int t,
            int months, int window, double growth) {
        List<double[]> out = new ArrayList<>();
        for (int i = t - window; i < t; i++) {
            if (i < 0) {
                continue;
            }
            double fitted;
            if (months >= 18 && i - 12 >= 0) {
                fitted = series[i - 12] * Math.pow(1 + growth, 12);
            } else if (i >= 3) {
                fitted = Stats.mean(series, i - 3, i);
            } else {
                continue;
            }
            if (fitted > 0) {
                out.add(new double[] {series[i] / fitted});
            }
        }
        double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i)[0];
        }
        return result;
    }

    static int zeroMonths(double[] series, int last) {
        int zeros = 0;
        for (int i = Math.max(0, series.length - last); i < series.length; i++) {
            if (series[i] <= 0) {
                zeros++;
            }
        }
        return zeros;
    }

    /** {@code |mean(last 3) − mean(prior 9)| > 2 × sd(prior 9)}, needing twelve months; sd 0 is no break. */
    static boolean structuralBreak(double[] series, int months) {
        int t = series.length;
        if (months < 12 || t < 12) {
            return false;
        }
        double last3 = Stats.mean(series, t - 3, t);
        double prior9 = Stats.mean(series, t - 12, t - 3);
        double sd = Stats.sampleSd(series, t - 12, t - 3);
        if (sd == 0) {
            return false;
        }
        return Math.abs(last3 - prior9) > 2 * sd;
    }

    /** Rolling one-step backtest over the last {@code min(12, months − 6)} origins. */
    static Accuracy accuracy(double[] series, double[] deseasonalised, double[] index, int endMonth, int months) {
        int t = series.length;
        int origins = Math.min(12, months - 6);
        if (origins < 3) {
            return new Accuracy(0, 0, Math.max(0, origins), false);
        }
        double naiveSum = 0;
        double modelSum = 0;
        int counted = 0;
        for (int o = t - origins; o < t; o++) {
            double actual = series[o];
            if (actual <= 0) {
                continue;
            }
            int firstNonZero = -1;
            for (int i = 0; i < o; i++) {
                if (series[i] > 0) {
                    firstNonZero = i;
                    break;
                }
            }
            int history = firstNonZero < 0 ? 0 : o - firstNonZero;
            double growth = growth(deseasonalised, o, Math.min(history, 12));
            double level = level(deseasonalised, o, growth);
            double model = level * (1 + growth) * indexAt(index, endMonth, o - (t - 1));
            double naive = index != null && o - 12 >= 0 ? series[o - 12] : series[o - 1];
            naiveSum += Math.abs(actual - naive) / actual;
            modelSum += Math.abs(actual - model) / actual;
            counted++;
        }
        if (counted < 3) {
            return new Accuracy(0, 0, counted, false);
        }
        return new Accuracy(100 * naiveSum / counted, 100 * modelSum / counted, counted, true);
    }

    static Weights weights(double[] series, double[] deseasonalised, boolean seasonal, double growth, double beta,
            double r2, boolean defaultElasticity) {
        int t = series.length;
        double seasonality = 0;
        if (seasonal) {
            double varRaw = Stats.variance(series, 0, t);
            double varDeseason = Stats.variance(deseasonalised, 0, t);
            if (varRaw > 0) {
                seasonality = Stats.clamp(100 * (1 - varDeseason / varRaw), 0, 60);
            }
        }
        double trend = Stats.clamp(Math.abs(growth) * 2400, 0, 40);
        double ownPrice = defaultElasticity ? 0 : Stats.clamp(Math.abs(beta) * r2 * 40, 0, 30);
        double unexplained = Math.max(0, 100 - seasonality - trend - ownPrice);
        return new Weights(seasonality, trend, ownPrice, unexplained);
    }

    private static double sumLast(double[] values, int n) {
        double sum = 0;
        for (int i = Math.max(0, values.length - n); i < values.length; i++) {
            sum += values[i];
        }
        return sum;
    }
}

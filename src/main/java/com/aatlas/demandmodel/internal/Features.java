package com.aatlas.demandmodel.internal;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * What the model sees for one item-branch-week, and the target it predicts.
 *
 * <p>Price enters only <em>relative</em> to the item itself: how far this week's price sits
 * from the item's usual price, and the price against the competitor median where one was
 * observed (with a flag for "none"). The absolute price is deliberately left out, and so is the
 * move from the week before: in a week with no sales the price is carried forward, so "no
 * move" would be a signature for "no sales" that the trees learn and then read into every
 * forecast at a steady price. Across a catalogue it is mostly an item identifier (a $4 fitting and
 * a $220 fixture), and a tree that had split on it would answer a probe of the fitting's price
 * with the fixture's volumes. Each item's own level comes from its one-hot; category and
 * branch are one-hots too.
 *
 * <p>Two models share these features and differ in one group. The <em>forecast</em> model
 * also sees the previous weeks' demand (lags, an eight-week mean, how many of those weeks
 * sold at all), which is what makes a next-week forecast accurate. The <em>response</em>
 * model does not: with the lags in, the trees explain this week's level by last week's level,
 * which already carries what the old price produced, and the price's own effect is hidden in
 * them. Without the lags, the price has to explain it, and the probed slope is the demand's
 * settled response to price, which is what a pricing decision needs.
 *
 * <p>Season (sine and cosine of the week of year) is included only when the history spans well
 * over a year. With a single year, the week of year is a calendar index that identifies each
 * training week exactly, and the trees memorise it rather than learn a season.
 *
 * <p>The target is {@code log(1 + units)}: a forecast of zero is a real answer for a slow
 * line, and the error on a 400-unit week does not drown every 4-unit one.
 */
final class Features {

    static final String TARGET = "log_units";
    /** The season features' names; serving checks the model's feature map for the first to know if it trained with them. */
    static final String SEASON_SIN = "woy_sin";
    static final String SEASON_COS = "woy_cos";

    private Features() {
    }

    /**
     * The inputs for one week, from a training row or from a pair's context at a hypothetical price.
     *
     * @param withLags the forecast model's view (previous weeks' demand in); false for the response model
     */
    record Input(double price, double prevPrice, double compMedian, double[] lags, double medianPrice,
            LocalDate week, String category, String store, String item, boolean itemOneHot, boolean season,
            boolean withLags) {
    }

    record Vector(String[] names, double[] values) {
    }

    static Input at(WeeklyGrid.Row r, boolean itemOneHot, boolean season, boolean withLags) {
        return new Input(r.price(), r.prevPrice(), r.compMedian(), r.lags(), r.medianPrice(), r.week(), r.category(),
                r.store(), r.item(), itemOneHot, season, withLags);
    }

    /** The pair's next week at {@code price}, starting from where its history ends. */
    static Input at(WeeklyGrid.Context c, double price, boolean itemOneHot, boolean season, boolean withLags) {
        return new Input(price, c.lastPrice(), c.compMedian(), c.lags(), c.medianPrice(), c.lastWeek().plusWeeks(1),
                c.category(), c.store(), c.item(), itemOneHot, season, withLags);
    }

    static Vector of(Input in) {
        List<String> names = new ArrayList<>(20);
        List<Double> values = new ArrayList<>(20);
        double price = Math.max(in.price(), 0.01);
        put(names, values, "rel_price", in.medianPrice() > 0 ? Math.log(price / in.medianPrice()) : 0);
        boolean comp = in.compMedian() > 0;
        put(names, values, "comp_ratio", comp ? Math.log(price / in.compMedian()) : 0);
        put(names, values, "has_comp", comp ? 1 : 0);
        if (in.withLags()) {
            double[] lags = in.lags() == null ? new double[0] : in.lags();
            for (int i = 0; i < 4; i++) {
                put(names, values, "lag" + (i + 1), i < lags.length ? Math.log1p(Math.max(0, lags[i])) : 0);
            }
            double sum = 0;
            int active = 0;
            for (double l : lags) {
                sum += Math.max(0, l);
                if (l > 0) {
                    active++;
                }
            }
            put(names, values, "mean8", lags.length == 0 ? 0 : Math.log1p(sum / lags.length));
            put(names, values, "active8", lags.length == 0 ? 0 : (double) active / lags.length);
        }
        if (in.season()) {
            int woy = in.week().get(WeekFields.ISO.weekOfWeekBasedYear());
            double angle = 2 * Math.PI * woy / 52.18;
            put(names, values, SEASON_SIN, Math.sin(angle));
            put(names, values, SEASON_COS, Math.cos(angle));
        }
        if (in.category() != null && !in.category().isBlank()) {
            put(names, values, "cat=" + in.category().trim(), 1);
        }
        if (in.store() != null && !in.store().isBlank()) {
            put(names, values, "store=" + in.store().trim(), 1);
        }
        if (in.itemOneHot() && in.item() != null && !in.item().isBlank()) {
            put(names, values, "item=" + in.item().trim(), 1);
        }
        double[] v = new double[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i);
        }
        return new Vector(names.toArray(String[]::new), v);
    }

    static double target(double units) {
        return Math.log1p(Math.max(0, units));
    }

    static double units(double target) {
        return Math.max(0, Math.expm1(target));
    }

    private static void put(List<String> names, List<Double> values, String name, double value) {
        names.add(name);
        values.add(Double.isFinite(value) ? value : 0);
    }
}

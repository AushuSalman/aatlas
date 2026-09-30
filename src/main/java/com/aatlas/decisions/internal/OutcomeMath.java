package com.aatlas.decisions.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one applied price did, from the sales either side of it. Pure: no I/O, no clock.
 *
 * <p>Same-length windows before and after the decision, at the same branch: units and profit a
 * week in each, the average price each side actually sold at, and from them the changes. With a
 * real price move (2% or more) and sales on both sides, the observed price sensitivity is
 * {@code ln(q1/q0) / ln(p1/p0)} - how much volume moved for the price move. Too few sales either
 * side and nothing is claimed.
 */
final class OutcomeMath {

    /** Fewer transactions than this on a side and the window says nothing. */
    static final int MIN_TXNS_EACH_SIDE = 2;
    /** Fewer units than this across both sides and the window says nothing. */
    static final int MIN_UNITS_TOTAL = 6;
    /** A price move smaller than this is noise; no sensitivity is read from it. */
    static final double MIN_PRICE_MOVE_PCT = 2;
    /** Profit a week this far up or down is a verdict; inside it, neutral. */
    static final double VERDICT_BAND_PCT = 2;

    private OutcomeMath() {
    }

    /** One side of the decision: what sold in the window. */
    record Side(BigDecimal units, BigDecimal revenue, long txns) {
    }

    /**
     * @param status  {@code measured} or {@code insufficient}
     * @param verdict {@code worked}, {@code hurt}, {@code neutral} or {@code insufficient}
     */
    record Result(String status, String verdict, BigDecimal unitsBeforeWeek, BigDecimal unitsAfterWeek,
            BigDecimal priceBefore, BigDecimal priceAfter, BigDecimal profitBeforeWeek, BigDecimal profitAfterWeek,
            BigDecimal priceChangePct, BigDecimal volumeChangePct, BigDecimal profitChangePct,
            BigDecimal impliedElasticity, String label, String note) {
    }

    /**
     * @param unitCost   the item's cost; null measures revenue instead of profit, and says so
     * @param fallbackBefore the price before the decision when nothing sold before (the deal's baseline)
     * @param fallbackAfter  the price applied, when nothing sold after
     */
    static Result measure(Side before, Side after, int windowDays, BigDecimal unitCost, BigDecimal fallbackBefore,
            BigDecimal fallbackAfter) {
        double weeks = windowDays / 7.0;
        double q0 = num(before.units());
        double q1 = num(after.units());
        if (before.txns() < MIN_TXNS_EACH_SIDE || after.txns() < MIN_TXNS_EACH_SIDE || q0 + q1 < MIN_UNITS_TOTAL) {
            String note = "Too few sales to judge yet: " + fmt(q0) + " units in the " + windowDays + " days before, "
                    + fmt(q1) + " after.";
            return new Result("insufficient", "insufficient", bd(q0 / weeks), bd(q1 / weeks), null, null, null, null,
                    null, null, null, null, "Not enough sales yet", note);
        }
        double p0 = q0 > 0 ? num(before.revenue()) / q0 : num(fallbackBefore);
        double p1 = q1 > 0 ? num(after.revenue()) / q1 : num(fallbackAfter);
        double w0 = q0 / weeks;
        double w1 = q1 / weeks;
        boolean costed = unitCost != null && unitCost.signum() > 0;
        double c = costed ? unitCost.doubleValue() : 0;
        double profit0 = w0 * (p0 - c);
        double profit1 = w1 * (p1 - c);

        Double priceChange = p0 > 0 ? (p1 / p0 - 1) * 100 : null;
        Double volumeChange = w0 > 0 ? (w1 / w0 - 1) * 100 : null;
        Double profitChange = profit0 > 0 ? (profit1 / profit0 - 1) * 100 : null;
        Double elasticity = null;
        if (priceChange != null && Math.abs(priceChange) >= MIN_PRICE_MOVE_PCT && q0 > 0 && q1 > 0 && p0 > 0 && p1 > 0) {
            double e = Math.log(w1 / w0) / Math.log(p1 / p0);
            if (Double.isFinite(e)) {
                elasticity = Math.max(-6, Math.min(1, e));
            }
        }
        String verdict = profitChange == null ? "neutral"
                : profitChange >= VERDICT_BAND_PCT ? "worked"
                : profitChange <= -VERDICT_BAND_PCT ? "hurt" : "neutral";

        StringBuilder label = new StringBuilder();
        if (priceChange != null) {
            label.append("Price ").append(signed(priceChange)).append(" · ");
        }
        label.append("volume ").append(volumeChange == null ? "new" : signed(volumeChange));
        if (profitChange != null) {
            label.append(" · ").append(costed ? "profit " : "revenue ").append(signed(profitChange));
        }
        String note = (costed ? "Profit" : "Revenue (no cost on file)") + " a week: " + money(profit0) + " before, "
                + money(profit1) + " after, over " + windowDays + " days each side.";
        return new Result("measured", verdict, bd(w0), bd(w1), bd(p0), bd(p1), bd(profit0), bd(profit1),
                pct(priceChange), pct(volumeChange), pct(profitChange),
                elasticity == null ? null : BigDecimal.valueOf(elasticity).setScale(4, RoundingMode.HALF_UP),
                capitalise(label.toString()), note);
    }

    private static double num(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String signed(double v) {
        long r = Math.round(v);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + "%";
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String money(double v) {
        return String.format(java.util.Locale.ROOT, "$%,.2f", v);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

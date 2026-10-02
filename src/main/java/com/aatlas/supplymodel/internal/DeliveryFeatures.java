package com.aatlas.supplymodel.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What the delivery models see for one order, and the targets they predict.
 *
 * <p>The order itself: the quantity (log), the promised lead time, and the supplier, its
 * country, the item's category and the branch as one-hots. The supplier's record before the
 * order: how many orders it has had (log), how often it was late over the last ten, by how
 * many days on average, and how long they took, with a flag for "too few to say". Season
 * (sine and cosine of the day of year) only when the history spans well over a year, for the
 * same reason as the demand model: with one year, the date identifies the order.
 *
 * <p>Two targets: the slip in days (actual minus promised, negative when early), and late as
 * 0 or 1, which a regression forest turns into a probability.
 */
final class DeliveryFeatures {

    static final String SLIP = "slip_days";
    static final String LATE = "late";
    static final String SEASON_SIN = "doy_sin";

    private DeliveryFeatures() {
    }

    record Input(String supplierId, String country, String category, String store, double qty, double promisedDays,
            int prevOrders, double prevLateRate, double prevMeanSlip, double prevMeanActual, boolean hasPrev,
            LocalDate date, boolean season) {
    }

    record Vector(String[] names, double[] values) {
    }

    static Input at(OrderGrid.Row r, boolean season) {
        OrderGrid.Order o = r.order();
        return new Input(o.supplierId(), o.country(), o.category(), o.store(), o.qty(), o.promisedDays(),
                r.prevOrders(), r.prevLateRate(), r.prevMeanSlip(), r.prevMeanActual(), r.hasPrev(), o.orderDate(),
                season);
    }

    /** A hypothetical order from the supplier's record after its last order. */
    static Input at(OrderGrid.Context c, String category, String store, double qty, double promisedDays, LocalDate date,
            boolean season) {
        return new Input(c.supplierId(), c.country(), category, store, qty, promisedDays, c.orders(), c.lateRate(),
                c.meanSlip(), c.meanActual(), c.orders() >= OrderGrid.MIN_PREV, date, season);
    }

    static Vector of(Input in) {
        List<String> names = new ArrayList<>(16);
        List<Double> values = new ArrayList<>(16);
        put(names, values, "log_qty", Math.log1p(Math.max(0, in.qty())));
        put(names, values, "promised_days", Math.max(0, in.promisedDays()));
        put(names, values, "prev_orders", Math.log1p(Math.max(0, in.prevOrders())));
        put(names, values, "has_prev", in.hasPrev() ? 1 : 0);
        put(names, values, "prev_late_rate", in.hasPrev() ? in.prevLateRate() : 0);
        put(names, values, "prev_mean_slip", in.hasPrev() ? in.prevMeanSlip() : 0);
        put(names, values, "prev_mean_actual", in.hasPrev() ? in.prevMeanActual() : 0);
        if (in.season() && in.date() != null) {
            double angle = 2 * Math.PI * in.date().getDayOfYear() / 365.25;
            put(names, values, SEASON_SIN, Math.sin(angle));
            put(names, values, "doy_cos", Math.cos(angle));
        }
        oneHot(names, values, "supplier", in.supplierId());
        oneHot(names, values, "country", in.country());
        oneHot(names, values, "cat", in.category());
        oneHot(names, values, "store", in.store());
        double[] v = new double[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i);
        }
        return new Vector(names.toArray(String[]::new), v);
    }

    private static void oneHot(List<String> names, List<Double> values, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            put(names, values, prefix + "=" + value.trim(), 1);
        }
    }

    private static void put(List<String> names, List<Double> values, String name, double value) {
        names.add(name);
        values.add(Double.isFinite(value) ? value : 0);
    }
}

package com.aatlas.supplymodel.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The training rows: one per received purchase order, with what was knowable about the
 * supplier when the order was placed - its record over the previous {@value #TRAIL} orders
 * (how often late, by how much, how long they took, what it promised). Only earlier orders
 * count, never the order itself or later ones, so the features at training time are what the
 * features at forecast time will be. The {@link Context} per supplier is its record after the
 * last order: what a forecast starts from.
 *
 * <p>Pure: no clock, no database.
 */
final class OrderGrid {

    /** Previous orders a supplier's trailing record is taken over. */
    static final int TRAIL = 10;
    /** Previous orders before the trailing record is trusted as a feature or a baseline. */
    static final int MIN_PREV = 3;

    private OrderGrid() {
    }

    /** A received order. {@code slip} is actual minus promised days: positive is late. */
    record Order(LocalDate orderDate, String supplierId, String supplierName, String country, String item,
            String category, String store, double qty, double promisedDays, double actualDays, boolean late) {

        double slip() {
            return actualDays - promisedDays;
        }
    }

    /** An order with the supplier's trailing record before it. */
    record Row(Order order, int prevOrders, double prevLateRate, double prevMeanSlip, double prevMeanActual,
            boolean hasPrev) {
    }

    /** A supplier after its last order. {@code promisedByItem} is the mean promised lead time per item it supplied. */
    record Context(String supplierId, String supplierName, String country, int orders, int late, double lateRate,
            double meanSlip, double meanActual, double meanPromised, double meanQty, LocalDate lastOrder,
            Map<String, Double> promisedByItem) {
    }

    record Grid(List<Row> rows, Map<String, Context> contexts, LocalDate firstOrder, LocalDate lastOrder) {
    }

    static Grid build(List<Order> orders) {
        List<Order> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparing(Order::orderDate));
        Map<String, List<Order>> history = new HashMap<>();
        List<Row> rows = new ArrayList<>(sorted.size());
        for (Order o : sorted) {
            List<Order> prev = history.computeIfAbsent(o.supplierId(), k -> new ArrayList<>());
            List<Order> trail = prev.subList(Math.max(0, prev.size() - TRAIL), prev.size());
            rows.add(new Row(o, prev.size(), rate(trail), meanSlip(trail), meanActual(trail), prev.size() >= MIN_PREV));
            prev.add(o);
        }
        Map<String, Context> contexts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Order>> e : history.entrySet()) {
            List<Order> all = e.getValue();
            List<Order> trail = all.subList(Math.max(0, all.size() - TRAIL), all.size());
            Order last = all.get(all.size() - 1);
            Map<String, List<Double>> promised = new HashMap<>();
            double qty = 0;
            double promisedAll = 0;
            int late = 0;
            for (Order o : all) {
                promised.computeIfAbsent(o.item(), k -> new ArrayList<>()).add(o.promisedDays());
                qty += o.qty();
                promisedAll += o.promisedDays();
                if (o.late()) {
                    late++;
                }
            }
            Map<String, Double> promisedByItem = new LinkedHashMap<>();
            promised.forEach((item, days) -> promisedByItem.put(item,
                    days.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
            contexts.put(e.getKey(), new Context(e.getKey(), last.supplierName(), last.country(), all.size(), late,
                    rate(trail), meanSlip(trail), meanActual(trail), promisedAll / all.size(), qty / all.size(),
                    last.orderDate(), promisedByItem));
        }
        LocalDate first = sorted.isEmpty() ? null : sorted.get(0).orderDate();
        LocalDate last = sorted.isEmpty() ? null : sorted.get(sorted.size() - 1).orderDate();
        return new Grid(rows, contexts, first, last);
    }

    static double rate(List<Order> orders) {
        if (orders.isEmpty()) {
            return 0;
        }
        long late = orders.stream().filter(Order::late).count();
        return (double) late / orders.size();
    }

    static double meanSlip(List<Order> orders) {
        return orders.stream().mapToDouble(Order::slip).average().orElse(0);
    }

    static double meanActual(List<Order> orders) {
        return orders.stream().mapToDouble(Order::actualDays).average().orElse(0);
    }
}

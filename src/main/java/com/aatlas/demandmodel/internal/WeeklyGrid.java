package com.aatlas.demandmodel.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The training grid: one row per item, branch and week, dense from the pair's first sale to
 * the last complete week, so weeks with no sales are zeros the model learns from rather than
 * gaps it never sees.
 *
 * <p>The price in a week with no sales is the last price seen (what the branch would have
 * charged); cost is carried the same way. The competitor median is the median of every
 * observation for the item in the eight weeks up to the week's end, zero when there was none.
 * Each row carries the previous eight weeks' units as lags, the item's own median price over
 * the window for the "how far from usual" feature, and the previous week's price for the move.
 * The {@link Context} per pair is the state after the last week: what a forecast for next week
 * starts from.
 *
 * <p>Pure: no clock, no database. Weeks start on Monday, as {@code date_trunc('week')} does.
 */
final class WeeklyGrid {

    /** Previous weeks' units carried on each row. */
    static final int LAGS = 8;
    /** How far back a competitor observation still counts for a week. */
    static final int COMPETITOR_LOOKBACK_DAYS = 56;

    private WeeklyGrid() {
    }

    /** A week with sales, as the database aggregates it. {@code avgCost} is 0 when no line carried a cost. */
    record SalesWeek(String item, String store, String category, LocalDate week, double units, double avgPrice,
            double avgCost) {
    }

    record CompetitorObs(String item, LocalDate observed, double price) {
    }

    /** One training row. {@code lags[0]} is last week's units, {@code lags[1]} the week before, and so on. */
    record Row(String pair, String item, String store, String category, LocalDate week, double units, double price,
            double prevPrice, double cost, double compMedian, double[] lags, double medianPrice) {
    }

    /** The pair after its last week: what a forecast for the week after starts from. */
    record Context(String item, String store, String category, LocalDate lastWeek, double lastPrice, double cost,
            double compMedian, double[] lags, double medianPrice, int weeks, double units) {
    }

    /**
     * @param firstWeek the earliest week with a sale across the tenant: an upload that starts mid-week makes it a
     *        partial week, so the trainer leaves its rows out
     * @param spanWeeks weeks from {@code firstWeek} to {@code lastWeek} inclusive
     */
    record Grid(List<Row> rows, Map<String, Context> contexts, LocalDate lastWeek, LocalDate firstWeek, int spanWeeks) {
    }

    static String pairKey(String item, String store) {
        return item + "@" + store;
    }

    /** The Monday of the last week that has fully elapsed before {@code today}. */
    static LocalDate lastCompleteWeek(LocalDate today) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /**
     * @param minWeeks pairs spanning fewer weeks than this (first sale to {@code lastWeek}) are left out:
     *        too little to hold anything back from
     */
    static Grid build(List<SalesWeek> sales, List<CompetitorObs> competitors, LocalDate lastWeek, int minWeeks) {
        Map<String, List<SalesWeek>> byPair = new LinkedHashMap<>();
        for (SalesWeek s : sales) {
            if (s.week().isAfter(lastWeek)) {
                continue;
            }
            byPair.computeIfAbsent(pairKey(s.item(), s.store()), k -> new ArrayList<>()).add(s);
        }
        Map<String, List<CompetitorObs>> compByItem = new HashMap<>();
        for (CompetitorObs c : competitors) {
            if (c.price() > 0) {
                compByItem.computeIfAbsent(c.item(), k -> new ArrayList<>()).add(c);
            }
        }
        compByItem.values().forEach(l -> l.sort(Comparator.comparing(CompetitorObs::observed)));

        LocalDate firstWeek = byPair.values().stream().flatMap(List::stream).map(SalesWeek::week)
                .min(Comparator.naturalOrder()).orElse(lastWeek);
        int spanWeeks = (int) ChronoUnit.WEEKS.between(firstWeek, lastWeek) + 1;
        List<Row> rows = new ArrayList<>();
        Map<String, Context> contexts = new LinkedHashMap<>();
        for (Map.Entry<String, List<SalesWeek>> e : byPair.entrySet()) {
            List<SalesWeek> weeks = new ArrayList<>(e.getValue());
            weeks.sort(Comparator.comparing(SalesWeek::week));
            SalesWeek head = weeks.get(0);
            LocalDate first = head.week();
            int span = (int) ChronoUnit.WEEKS.between(first, lastWeek) + 1;
            if (span < minWeeks) {
                continue;
            }
            Map<LocalDate, SalesWeek> byWeek = new HashMap<>();
            for (SalesWeek w : weeks) {
                byWeek.put(w.week(), w);
            }
            double medianPrice = median(weeks.stream().mapToDouble(SalesWeek::avgPrice).filter(p -> p > 0).toArray());
            List<CompetitorObs> comps = compByItem.getOrDefault(head.item(), List.of());

            double price = head.avgPrice();
            double cost = head.avgCost();
            double[] lags = new double[LAGS];
            double total = 0;
            for (LocalDate w = first; !w.isAfter(lastWeek); w = w.plusWeeks(1)) {
                SalesWeek s = byWeek.get(w);
                double prev = price;
                if (s != null && s.avgPrice() > 0) {
                    price = s.avgPrice();
                }
                if (s != null && s.avgCost() > 0) {
                    cost = s.avgCost();
                }
                double units = s == null ? 0 : Math.max(0, s.units());
                rows.add(new Row(e.getKey(), head.item(), head.store(), head.category(), w, units, price, prev, cost,
                        competitorMedian(comps, w), lags.clone(), medianPrice));
                System.arraycopy(lags, 0, lags, 1, LAGS - 1);
                lags[0] = units;
                total += units;
            }
            contexts.put(e.getKey(), new Context(head.item(), head.store(), head.category(), lastWeek, price, cost,
                    competitorMedian(comps, lastWeek), lags.clone(), medianPrice, span, total));
        }
        return new Grid(rows, contexts, lastWeek, firstWeek, spanWeeks);
    }

    /** The median of the item's competitor observations in the lookback up to the week's end; 0 with none. */
    static double competitorMedian(List<CompetitorObs> sorted, LocalDate week) {
        LocalDate end = week.plusDays(6);
        LocalDate start = end.minusDays(COMPETITOR_LOOKBACK_DAYS);
        List<Double> prices = new ArrayList<>();
        for (CompetitorObs c : sorted) {
            if (c.observed().isAfter(end)) {
                break;
            }
            if (c.observed().isAfter(start)) {
                prices.add(c.price());
            }
        }
        return prices.isEmpty() ? 0 : median(prices.stream().mapToDouble(Double::doubleValue).toArray());
    }

    static double median(double[] xs) {
        if (xs.length == 0) {
            return 0;
        }
        double[] s = xs.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }
}

package com.aatlas.analytics.internal.ledger;

import com.aatlas.analytics.internal.fixtures.Fixtures;
import com.aatlas.analytics.internal.fixtures.SupplierFixture;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Everything the Buying insights dashboard draws, for one window and one set of filters, all
 * reduced from the full purchase-order ledger at read time. Ported field for field and call
 * for call from {@code platform/procurement.ts}'s {@code computeBuyAnalytics} and its helpers
 * ({@code buildMix}, {@code stableColours}, {@code weighted}, {@code deltaPct}).
 *
 * <p>Pure and stateless: every method takes the full (unfiltered, all-time) ledger for a
 * tenant as {@code allRows} - callers own fetching that from {@code purchase_order} and
 * mapping it onto {@link PoRow}.
 */
public final class BuyAnalyticsEngine {

    private BuyAnalyticsEngine() {
    }

    private static double round2(double n) {
        return Rounding.round2(n);
    }

    /** The oldest order on file - the custom range picker will not go behind it. */
    public static LocalDate ledgerStart(List<PoRow> allRows) {
        return allRows.isEmpty() ? null : allRows.get(allRows.size() - 1).date();
    }

    private static double sum(List<PoRow> rows, ToDoubleFunction<PoRow> f) {
        double total = 0;
        for (PoRow r : rows) {
            total += f.applyAsDouble(r);
        }
        return round2(total);
    }

    private static boolean inRange(PoRow r, LocalDate from, LocalDate to) {
        return !r.date().isBefore(from) && !r.date().isAfter(to);
    }

    private static List<PoRow> applyFilters(List<PoRow> rows, Filters f) {
        return rows.stream()
                .filter(r -> Filters.ALL.equals(f.branch()) || r.branchId().equals(f.branch()))
                .filter(r -> Filters.ALL.equals(f.category()) || r.category().equals(f.category()))
                .toList();
    }

    private static Double deltaPct(double value, double previous, boolean comparable) {
        if (!comparable || previous == 0) {
            return null;
        }
        return round2(((value - previous) / Math.abs(previous)) * 100);
    }

    /** Weighted mean, guarded - an empty window returns 0 rather than NaN. */
    private static double weighted(List<PoRow> rows, ToDoubleFunction<PoRow> value, ToDoubleFunction<PoRow> weight) {
        double w = 0;
        for (PoRow r : rows) {
            w += weight.applyAsDouble(r);
        }
        if (w == 0) {
            return 0;
        }
        double num = 0;
        for (PoRow r : rows) {
            num += value.applyAsDouble(r) * weight.applyAsDouble(r);
        }
        return round2(num / w);
    }

    private static Kpi kpi(boolean comparable, double value, double previous, List<Double> series) {
        return new Kpi(value, previous, deltaPct(value, previous, comparable), series);
    }

    /**
     * Stable colour assignment, computed once from the WHOLE ledger so the mapping never
     * moves when the window or filters change. The six biggest names by all-time spend get
     * slots 0-5; everyone else is -1 (grey / folded into "Other").
     */
    private static Map<String, Integer> stableColours(List<PoRow> allRows, Function<PoRow, String> keyOf) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (PoRow r : allRows) {
            totals.merge(keyOf.apply(r), r.spend(), Double::sum);
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            out.put(ranked.get(i).getKey(), i < 6 ? i : -1);
        }
        return out;
    }

    private record MixCtx(List<PoRow> rows, List<PoRow> prev, List<List<PoRow>> byBucketRows, double total,
                           double prevTotal, boolean comparable) {
    }

    private static List<MixSlice> buildMix(
            MixCtx ctx, Function<PoRow, String> keyOf, Function<PoRow, String> labelOf, Map<String, Integer> colours) {
        Map<String, Double> now = new LinkedHashMap<>();
        for (PoRow r : ctx.rows()) {
            now.merge(keyOf.apply(r), r.spend(), Double::sum);
        }
        Map<String, Double> before = new LinkedHashMap<>();
        for (PoRow r : ctx.prev()) {
            before.merge(keyOf.apply(r), r.spend(), Double::sum);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (PoRow r : ctx.rows()) {
            labels.putIfAbsent(keyOf.apply(r), labelOf.apply(r));
        }
        for (PoRow r : ctx.prev()) {
            labels.putIfAbsent(keyOf.apply(r), labelOf.apply(r));
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> e : now.entrySet()) {
            if (e.getValue() > 0) {
                ranked.add(e);
            }
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<MixSlice> head = new ArrayList<>();
        List<MixSlice> tail = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            String key = e.getKey();
            double value = round2(e.getValue());
            double prevValue = round2(before.getOrDefault(key, 0.0));
            double sharePct = ctx.total() != 0 ? round2((value / ctx.total()) * 100) : 0;
            double prevSharePct = ctx.prevTotal() != 0 ? round2((prevValue / ctx.prevTotal()) * 100) : 0;
            Double changePts = ctx.comparable() && !ctx.prev().isEmpty() ? round2(sharePct - prevSharePct) : null;
            List<Double> series = new ArrayList<>();
            for (List<PoRow> bucketRows : ctx.byBucketRows()) {
                double s = 0;
                for (PoRow r : bucketRows) {
                    if (keyOf.apply(r).equals(key)) {
                        s += r.spend();
                    }
                }
                series.add(round2(s));
            }
            int colorIndex = colours.getOrDefault(key, -1);
            MixSlice slice = new MixSlice(key, labels.getOrDefault(key, key), value, sharePct, prevValue, prevSharePct,
                    changePts, series, colorIndex);
            if (colorIndex >= 0) {
                head.add(slice);
            } else {
                tail.add(slice);
            }
        }
        if (tail.isEmpty()) {
            return head;
        }
        double value = round2(tail.stream().mapToDouble(MixSlice::value).sum());
        double prevValue = round2(tail.stream().mapToDouble(MixSlice::prevValue).sum());
        double sharePct = ctx.total() != 0 ? round2((value / ctx.total()) * 100) : 0;
        double prevSharePct = ctx.prevTotal() != 0 ? round2((prevValue / ctx.prevTotal()) * 100) : 0;
        Double changePts = ctx.comparable() && !ctx.prev().isEmpty() ? round2(sharePct - prevSharePct) : null;
        List<Double> series = new ArrayList<>();
        int bucketCount = ctx.byBucketRows().size();
        for (int i = 0; i < bucketCount; i++) {
            double s = 0;
            for (MixSlice t : tail) {
                s += t.series().get(i);
            }
            series.add(round2(s));
        }
        head.add(new MixSlice("__other", "Other (" + tail.size() + ")", value, sharePct, prevValue, prevSharePct,
                changePts, series, -1));
        return head;
    }

    public static BuyAnalytics compute(DateRange range, Filters filters, List<PoRow> allRows) {
        DateRange compare = DateRanges.previousRange(range);
        LocalDate ledgerStart = ledgerStart(allRows);
        boolean comparable = ledgerStart != null && !compare.from().isBefore(ledgerStart);
        DateRanges.Buckets bf = DateRanges.bucketsFor(range);
        List<Bucket> buckets = bf.buckets();

        List<PoRow> scoped = applyFilters(allRows, filters);
        List<PoRow> rows = scoped.stream().filter(r -> inRange(r, range.from(), range.to())).toList();
        List<PoRow> prev = scoped.stream().filter(r -> inRange(r, compare.from(), compare.to())).toList();

        Predicate<PoRow> hasReceived = r -> r.receivedDate() != null;
        List<PoRow> received = scoped.stream().filter(hasReceived)
                .filter(r -> inRange2(r.receivedDate(), range.from(), range.to())).toList();
        List<PoRow> receivedPrev = scoped.stream().filter(hasReceived)
                .filter(r -> inRange2(r.receivedDate(), compare.from(), compare.to())).toList();

        List<List<PoRow>> byBucketRows = new ArrayList<>();
        for (Bucket b : buckets) {
            byBucketRows.add(rows.stream().filter(r -> inRange(r, b.from(), b.to())).toList());
        }

        List<TimelinePoint> timeline = new ArrayList<>();
        for (int bi = 0; bi < buckets.size(); bi++) {
            Bucket bucket = buckets.get(bi);
            List<PoRow> br = byBucketRows.get(bi);
            double spendIn = sum(br, PoRow::spend);
            double excess = sum(br, PoRow::leaked);
            List<PoRow> rec = scoped.stream().filter(hasReceived)
                    .filter(r -> inRange2(r.receivedDate(), bucket.from(), bucket.to())).toList();
            timeline.add(new TimelinePoint(
                    bucket, spendIn, round2(spendIn - excess), excess, sum(br, PoRow::saved), excess, br.size(),
                    br.stream().mapToInt(PoRow::qty).sum(),
                    weighted(br, PoRow::landed, r -> r.qty()),
                    br.isEmpty() ? 0 : round2((br.stream().filter(PoRow::followed).count() * 100.0) / br.size()),
                    rec.isEmpty() ? 0 : round2((rec.stream().filter(PoRow::onTime).count() * 100.0) / rec.size())));
        }

        double spend = sum(rows, PoRow::spend);
        double saved = sum(rows, PoRow::saved);
        double leaked = sum(rows, PoRow::leaked);
        double baselineSpend = sum(rows, PoRow::baselineSpend);

        double captureNow = rows.isEmpty() ? 0 : round2((rows.stream().filter(PoRow::followed).count() * 100.0) / rows.size());
        double capturePrev = prev.isEmpty() ? 0 : round2((prev.stream().filter(PoRow::followed).count() * 100.0) / prev.size());
        double onTimeNow = received.isEmpty() ? 0 : round2((received.stream().filter(PoRow::onTime).count() * 100.0) / received.size());
        double onTimePrev = receivedPrev.isEmpty() ? 0 : round2((receivedPrev.stream().filter(PoRow::onTime).count() * 100.0) / receivedPrev.size());

        // -- Suppliers ----------------------------------------------------------
        List<String> supplierIds = new ArrayList<>();
        for (PoRow r : rows) {
            if (!supplierIds.contains(r.supplierId())) {
                supplierIds.add(r.supplierId());
            }
        }
        for (PoRow r : received) {
            if (!supplierIds.contains(r.supplierId())) {
                supplierIds.add(r.supplierId());
            }
        }
        List<SupplierRow> suppliers = new ArrayList<>();
        for (String id : supplierIds) {
            List<PoRow> mine = rows.stream().filter(r -> r.supplierId().equals(id)).toList();
            List<PoRow> rec = received.stream().filter(r -> r.supplierId().equals(id)).toList();
            SupplierFixture s = Fixtures.findSupplier(id);
            double mySpend = sum(mine, PoRow::spend);
            double onTimePct = rec.isEmpty() ? 0 : round2((rec.stream().filter(PoRow::onTime).count() * 100.0) / rec.size());
            double sharePct = spend != 0 ? round2((mySpend / spend) * 100) : 0;
            String risk = (onTimePct < 82 && sharePct > 12) ? "high" : (onTimePct < 88 || sharePct > 28) ? "watch" : "low";
            int avgLeadDays = rec.isEmpty()
                    ? (s != null ? s.leadTimeDays() : 0)
                    : (int) Math.round(rec.stream().mapToInt(PoRow::actualDays).average().orElse(0));
            List<Double> series = new ArrayList<>();
            for (List<PoRow> br : byBucketRows) {
                series.add(sum(br.stream().filter(r -> r.supplierId().equals(id)).toList(), PoRow::spend));
            }
            suppliers.add(new SupplierRow(
                    id,
                    s != null ? s.name() : id,
                    s != null ? s.country() : "",
                    s != null ? s.vendorCode() : "",
                    mySpend, sharePct, mine.size(), mine.stream().mapToInt(PoRow::qty).sum(),
                    sum(mine, PoRow::saved), sum(mine, PoRow::leaked),
                    weighted(mine, PoRow::landed, r -> r.qty()),
                    s != null ? s.priceIndex() : 0,
                    onTimePct, avgLeadDays,
                    s != null ? s.qualityPpm() : 0,
                    s != null ? s.creditDays() : 0,
                    risk, series, 0));
        }
        suppliers.sort((a, b) -> Double.compare(b.spend(), a.spend()));
        Map<String, Integer> supplierColour = stableColours(allRows, PoRow::supplierId);
        List<SupplierRow> supplierRows = new ArrayList<>();
        for (SupplierRow r : suppliers) {
            supplierRows.add(new SupplierRow(r.id(), r.name(), r.country(), r.vendorCode(), r.spend(), r.sharePct(),
                    r.orders(), r.units(), r.saved(), r.leaked(), r.avgLanded(), r.priceIndex(), r.onTimePct(),
                    r.avgLeadDays(), r.qualityPpm(), r.creditDays(), r.risk(), r.series(),
                    supplierColour.getOrDefault(r.id(), -1)));
        }

        MixCtx mixCtx = new MixCtx(rows, prev, byBucketRows, spend, sum(prev, PoRow::spend), comparable);
        List<MixSlice> supplierMix = buildMix(mixCtx, PoRow::supplierId, PoRow::supplierName, supplierColour);
        List<MixSlice> categoryMix = buildMix(mixCtx, PoRow::category, r -> Categories.labelOf(r.category()),
                stableColours(allRows, PoRow::category));
        List<MixSlice> regionMix = buildMix(mixCtx, PoRow::regionKey, PoRow::regionLabel, stableColours(allRows, PoRow::regionKey));
        List<MixSlice> originMix = buildMix(mixCtx, PoRow::country, PoRow::country, stableColours(allRows, PoRow::country));

        // -- Opportunities --------------------------------------------------------
        List<String> oppKeys = new ArrayList<>();
        for (PoRow r : rows) {
            if (r.leaked() > 0) {
                String key = r.itemNumber() + "|" + r.supplierId();
                if (!oppKeys.contains(key)) {
                    oppKeys.add(key);
                }
            }
        }
        List<Opportunity> opportunities = new ArrayList<>();
        for (String key : oppKeys) {
            String[] parts = key.split("\\|", 2);
            String itemNumber = parts[0];
            String supplierId = parts[1];
            List<PoRow> mine = rows.stream()
                    .filter(r -> r.itemNumber().equals(itemNumber) && r.supplierId().equals(supplierId)).toList();
            double avgLanded = weighted(mine, PoRow::landed, r -> r.qty());
            double avgTarget = weighted(mine, PoRow::target, r -> r.qty());
            opportunities.add(new Opportunity(
                    key, itemNumber, mine.get(0).description(), mine.get(0).category(), mine.get(0).supplierName(),
                    sum(mine, PoRow::leaked), mine.stream().mapToInt(PoRow::qty).sum(), mine.size(),
                    avgTarget != 0 ? round2(((avgLanded - avgTarget) / avgTarget) * 100) : 0, avgLanded, avgTarget));
        }
        opportunities = new ArrayList<>(opportunities);
        opportunities.sort((a, b) -> Double.compare(b.leaked(), a.leaked()));
        if (opportunities.size() > 7) {
            opportunities = opportunities.subList(0, 7);
        }

        // -- Delivery ---------------------------------------------------------------
        List<DeliveryPoint> delivery = new ArrayList<>();
        for (Bucket bucket : buckets) {
            List<PoRow> rec = scoped.stream().filter(hasReceived)
                    .filter(r -> inRange2(r.receivedDate(), bucket.from(), bucket.to())).toList();
            long onTimeCount = rec.stream().filter(PoRow::onTime).count();
            delivery.add(new DeliveryPoint(bucket, rec.isEmpty() ? 0 : round2((onTimeCount * 100.0) / rec.size()),
                    (int) (rec.size() - onTimeCount), (int) onTimeCount, rec.size(),
                    rec.isEmpty() ? 0 : (int) Math.round(rec.stream().mapToInt(PoRow::actualDays).average().orElse(0))));
        }

        List<PoRow> openRows = rows.stream().filter(r -> "open".equals(r.status())).toList();
        List<PoRow> transitRows = rows.stream().filter(r -> "in-transit".equals(r.status())).toList();

        double prevBaseline = sum(prev, PoRow::baselineSpend);
        double prevSpend = sum(prev, PoRow::spend);

        List<PoRow> lateLines = received.stream().filter(r -> !r.onTime())
                .sorted((a, b) -> Integer.compare(b.daysLate(), a.daysLate()))
                .limit(6)
                .toList();

        return new BuyAnalytics(
                range, compare, comparable, bf.unit().name().toLowerCase(java.util.Locale.ROOT),
                rows, received, rows.isEmpty(),

                kpi(comparable, spend, prevSpend, timeline.stream().map(TimelinePoint::spend).toList()),
                kpi(comparable, saved, sum(prev, PoRow::saved), timeline.stream().map(TimelinePoint::saved).toList()),
                kpi(comparable, leaked, sum(prev, PoRow::leaked), timeline.stream().map(TimelinePoint::leaked).toList()),
                kpi(comparable,
                        baselineSpend != 0 ? round2((spend / baselineSpend) * 100) : 0,
                        prevBaseline != 0 ? round2((prevSpend / prevBaseline) * 100) : 0,
                        byBucketRows.stream().map(br -> {
                            double b = sum(br, PoRow::baselineSpend);
                            return b != 0 ? round2((sum(br, PoRow::spend) / b) * 100) : 0;
                        }).toList()),
                kpi(comparable, weighted(rows, PoRow::landed, r -> r.qty()), weighted(prev, PoRow::landed, r -> r.qty()),
                        timeline.stream().map(TimelinePoint::avgUnitCost).toList()),
                kpi(comparable, captureNow, capturePrev, timeline.stream().map(TimelinePoint::captureRatePct).toList()),
                kpi(comparable, onTimeNow, onTimePrev, delivery.stream().map(DeliveryPoint::onTimePct).toList()),
                kpi(comparable,
                        received.isEmpty() ? 0 : round2(received.stream().mapToInt(PoRow::actualDays).average().orElse(0)),
                        receivedPrev.isEmpty() ? 0 : round2(receivedPrev.stream().mapToInt(PoRow::actualDays).average().orElse(0)),
                        delivery.stream().map(d -> (double) d.avgLeadDays()).toList()),
                kpi(comparable, rows.size(), prev.size(), timeline.stream().map(t -> (double) t.orders()).toList()),

                (int) rows.stream().map(PoRow::supplierId).distinct().count(),
                (int) prev.stream().map(PoRow::supplierId).distinct().count(),
                rows.size(),
                rows.stream().mapToInt(PoRow::qty).sum(),
                baselineSpend,
                baselineSpend != 0 ? round2((saved / baselineSpend) * 100) : 0,

                timeline, supplierRows, supplierMix, categoryMix, regionMix, originMix,
                new BuyAnalytics.LandedSplit(
                        sum(rows, r -> r.exWorks() * r.qty()),
                        sum(rows, r -> r.freight() * r.qty()),
                        sum(rows, r -> r.duty() * r.qty())),
                opportunities,
                new BuyAnalytics.Status(
                        (int) rows.stream().filter(r -> "received".equals(r.status())).count(),
                        transitRows.size(), openRows.size(), sum(openRows, PoRow::spend), sum(transitRows, PoRow::spend)),
                delivery,
                lateLines);
    }

    private static boolean inRange2(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    /**
     * A by-branch spend breakdown, the same shape and the same {@link #buildMix} rule as
     * {@code supplierMix}/{@code categoryMix}/{@code regionMix}/{@code originMix} - the one
     * dimension {@code GET /analytics/procurement/mix} supports beyond the four the frontend's
     * {@code BuyAnalytics} already carries (see {@code API-BRIEF.md}'s Group N: "by
     * supplier/category/branch/origin"). Kept out of {@link #compute} so the full dashboard
     * payload stays an exact field-for-field port with nothing extra for the golden test to
     * trip over.
     */
    public static List<MixSlice> branchMix(DateRange range, Filters filters, List<PoRow> allRows) {
        DateRange compare = DateRanges.previousRange(range);
        LocalDate ledgerStart = ledgerStart(allRows);
        boolean comparable = ledgerStart != null && !compare.from().isBefore(ledgerStart);
        List<Bucket> buckets = DateRanges.bucketsFor(range).buckets();

        List<PoRow> scoped = applyFilters(allRows, filters);
        List<PoRow> rows = scoped.stream().filter(r -> inRange(r, range.from(), range.to())).toList();
        List<PoRow> prev = scoped.stream().filter(r -> inRange(r, compare.from(), compare.to())).toList();
        List<List<PoRow>> byBucketRows = new ArrayList<>();
        for (Bucket b : buckets) {
            byBucketRows.add(rows.stream().filter(r -> inRange(r, b.from(), b.to())).toList());
        }
        MixCtx ctx = new MixCtx(rows, prev, byBucketRows, sum(rows, PoRow::spend), sum(prev, PoRow::spend), comparable);
        return buildMix(ctx, PoRow::branchId, PoRow::branchName, stableColours(allRows, PoRow::branchId));
    }
}

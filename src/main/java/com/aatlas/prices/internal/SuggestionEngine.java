package com.aatlas.prices.internal;

import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.PricingMath;
import com.aatlas.history.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Set your prices": one suggested list price per item (spec A §4), with the basis spelled
 * out so a prospect can read where the number came from and the UI can recompute it when a
 * slider moves. Pure: everything it needs is handed in.
 */
final class SuggestionEngine {

    static final String STATUS_OK = "ok";
    static final String STATUS_NEEDS_COST = "needs-cost";
    static final String REASON_NO_COST = "Add a cost, a competitor price or sales history to get a suggestion";

    static final BigDecimal BENCHMARK_WEIGHT_WITH_COMPETITOR = new BigDecimal("0.5");
    static final BigDecimal BENCHMARK_WEIGHT_WITH_PEER = new BigDecimal("0.6");
    static final BigDecimal RPP_SENSITIVITY = new BigDecimal("0.55");

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private SuggestionEngine() {
    }

    /**
     * Everything one suggestion needs.
     *
     * @param store null for a tenant-wide suggestion
     * @param cost the ladder's cost, null when none
     * @param currentPrice the ladder's current price, null when none
     * @param competitorObservations the latest observation per competitor, matched rows flagged
     * @param band the item's trailing-twelve-month price band, null when none
     * @param targetMarginOverride the request's per-category override, null when none
     */
    record Inputs(Catalogue.ProductRef product, Catalogue.StoreRef store, Resolved cost, Resolved currentPrice,
            List<CompetitorPrices.Observation> competitorObservations, SalesHistory.PriceBand band,
            Reference.Benchmark benchmark, BigDecimal targetMarginOverride, Reference.Commodity commodity,
            String currency) {
    }

    static SuggestionRow suggest(Inputs in) {
        Catalogue.ProductRef product = in.product();
        BigDecimal current = in.currentPrice() == null ? null : PricingMath.round2(in.currentPrice().value());
        String currentSource = in.currentPrice() == null ? null : in.currentPrice().source();
        if (in.cost() == null || in.cost().value() == null || in.cost().value().signum() <= 0) {
            return new SuggestionRow(product.itemNumber(), product.description(), product.category(),
                    product.subcategory(), product.unit(), product.commodity(), null, null, current, currentSource,
                    null, null, null, null, null, null, null, null, null, null, STATUS_NEEDS_COST, true,
                    REASON_NO_COST);
        }
        BigDecimal c = in.cost().value();
        Reference.Benchmark benchmark = in.benchmark();
        BigDecimal tm = in.targetMarginOverride() != null ? in.targetMarginOverride() : benchmark.targetMarginPct();
        BigDecimal lo = PricingMath.min(benchmark.lowMarginPct(), tm);
        BigDecimal hi = PricingMath.max(benchmark.highMarginPct(), tm);

        // 2. regional index, store variant only
        BigDecimal ri = BigDecimal.ONE;
        Catalogue.StoreRef store = in.store();
        if (store != null && store.rpp() != null) {
            ri = BigDecimal.ONE.subtract(store.rpp().subtract(PricingMath.HUNDRED)
                    .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP)
                    .multiply(RPP_SENSITIVITY)).setScale(PricingMath.RATIO_SCALE, RoundingMode.HALF_UP);
        }
        // 3. commodity drift, reference
        Reference.Commodity commodity = in.commodity();
        BigDecimal pct90 = commodity == null || commodity.pct90() == null ? BigDecimal.ZERO : commodity.pct90();
        BigDecimal cd = BigDecimal.ONE.add(pct90.multiply(BigDecimal.valueOf(PricingMath.COMMODITY_PASS_THROUGH))
                .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP));
        // 4. benchmark price with the multipliers inside (the wizard has no chain)
        BigDecimal atMargin = PricingMath.priceAtMargin(c, tm);
        BigDecimal b = PricingMath.money(atMargin.multiply(ri).multiply(cd));

        // 5. anchors
        CompetitorSummary competitor = competitor(in.competitorObservations());
        PeerSummary peer = in.band() == null || in.band().median() == null ? null
                : new PeerSummary(PricingMath.round2(in.band().median()), in.band().n());

        // 6. blend, band, rounding
        BigDecimal raw;
        String anchor;
        BigDecimal benchmarkWeight;
        BigDecimal anchorPrice;
        if (competitor != null) {
            benchmarkWeight = BENCHMARK_WEIGHT_WITH_COMPETITOR;
            raw = b.multiply(benchmarkWeight).add(competitor.median().multiply(BigDecimal.ONE.subtract(benchmarkWeight)));
            anchor = Anchor.COMPETITOR;
            anchorPrice = competitor.median();
        } else if (peer != null) {
            benchmarkWeight = BENCHMARK_WEIGHT_WITH_PEER;
            raw = b.multiply(benchmarkWeight).add(peer.median().multiply(BigDecimal.ONE.subtract(benchmarkWeight)));
            anchor = Anchor.PEER;
            anchorPrice = peer.median();
        } else {
            benchmarkWeight = BigDecimal.ONE;
            raw = b;
            anchor = Anchor.BENCHMARK;
            anchorPrice = b;
        }
        raw = PricingMath.money(raw);
        BigDecimal floor = PricingMath.priceAtMargin(c, lo);
        BigDecimal ceiling = PricingMath.priceAtMargin(c, hi);
        BigDecimal clamped = PricingMath.clamp(raw, floor, ceiling);
        BigDecimal suggested = PricingMath.roundPricePoint(clamped);
        if (ceiling != null && suggested.compareTo(ceiling) > 0) {
            suggested = PricingMath.roundPricePoint(clamped, RoundingMode.FLOOR);
        }
        if (floor != null && suggested.compareTo(floor) < 0) {
            suggested = PricingMath.roundPricePoint(clamped, RoundingMode.CEILING);
        }
        BigDecimal marginPct = PricingMath.marginPct(suggested, c);
        BigDecimal margin1 = marginPct == null ? null : marginPct.setScale(1, RoundingMode.HALF_UP);

        BenchmarkSummary benchmarkSummary = new BenchmarkSummary(tm, benchmark.lowMarginPct(),
                benchmark.highMarginPct(), ri.setScale(3, RoundingMode.HALF_UP), pct90, PricingMath.round2(b),
                benchmark.note(), benchmark.matched());

        Map<String, Object> basis = basis(in, c, atMargin, tm, benchmark, ri, cd, pct90, competitor, peer,
                benchmarkWeight, anchor, floor, ceiling, raw, suggested, margin1, b);

        return new SuggestionRow(product.itemNumber(), product.description(), product.category(),
                product.subcategory(), product.unit(), product.commodity(), PricingMath.round2(c),
                in.cost().source(), current, currentSource, anchor, PricingMath.round2(anchorPrice), competitor, peer,
                benchmarkSummary, suggested, margin1, PricingMath.round2(floor), PricingMath.round2(ceiling), basis,
                STATUS_OK, false, null);
    }

    /** Median, low and high over the matched observations when any match, else over all. */
    static CompetitorSummary competitor(List<CompetitorPrices.Observation> observations) {
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        List<CompetitorPrices.Observation> matched = observations.stream()
                .filter(CompetitorPrices.Observation::matched).toList();
        List<CompetitorPrices.Observation> basis = matched.isEmpty() ? observations : matched;
        double[] prices = basis.stream().mapToDouble(o -> o.price().doubleValue()).toArray();
        BigDecimal median = BigDecimal.valueOf(Stats.quantile(prices, 0.5)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal low = basis.stream().map(CompetitorPrices.Observation::price).reduce(PricingMath::min).orElse(null);
        BigDecimal high = basis.stream().map(CompetitorPrices.Observation::price).reduce(PricingMath::max).orElse(null);
        return new CompetitorSummary(median, PricingMath.round2(low), PricingMath.round2(high), basis.size(),
                !matched.isEmpty());
    }

    private static Map<String, Object> basis(Inputs in, BigDecimal c, BigDecimal atMargin, BigDecimal tm,
            Reference.Benchmark benchmark, BigDecimal ri, BigDecimal cd, BigDecimal pct90, CompetitorSummary competitor,
            PeerSummary peer, BigDecimal benchmarkWeight, String anchor, BigDecimal floor, BigDecimal ceiling,
            BigDecimal raw, BigDecimal suggested, BigDecimal marginPct, BigDecimal b) {
        Map<String, Object> basis = new LinkedHashMap<>();
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("value", PricingMath.round2(c));
        cost.put("source", in.cost().source());
        cost.put("asOf", in.cost().asOf() == null ? null : in.cost().asOf().toString());
        basis.put("cost", cost);

        Map<String, Object> bench = new LinkedHashMap<>();
        bench.put("category", benchmark.category());
        bench.put("subcategory", benchmark.subcategory());
        bench.put("targetMarginPct", tm);
        bench.put("lowMarginPct", benchmark.lowMarginPct());
        bench.put("highMarginPct", benchmark.highMarginPct());
        bench.put("price", PricingMath.round2(atMargin));
        bench.put("note", benchmark.note());
        bench.put("matched", benchmark.matched());
        bench.put("overridden", in.targetMarginOverride() != null);
        basis.put("benchmark", bench);

        Catalogue.StoreRef store = in.store();
        if (store != null && store.rpp() != null) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("store", store.storeCode());
            region.put("rpp", store.rpp());
            region.put("multiplier", ri.setScale(3, RoundingMode.HALF_UP));
            basis.put("regionalIndex", region);
        } else {
            basis.put("regionalIndex", null);
        }

        Reference.Commodity commodity = in.commodity();
        if (commodity != null && !"none".equals(commodity.key())) {
            Map<String, Object> com = new LinkedHashMap<>();
            com.put("key", commodity.key());
            com.put("pct90", pct90);
            com.put("asOf", commodity.asOf() == null ? null : commodity.asOf().toString());
            com.put("multiplier", cd.setScale(3, RoundingMode.HALF_UP));
            com.put("source", "reference");
            basis.put("commodity", com);
        } else {
            basis.put("commodity", null);
        }

        if (competitor != null) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("median", competitor.median());
            comp.put("low", competitor.low());
            comp.put("high", competitor.high());
            comp.put("observations", competitor.observations());
            comp.put("regionMatched", competitor.regionMatched());
            basis.put("competitor", comp);
        } else {
            basis.put("competitor", null);
        }
        if (peer != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("median", peer.median());
            p.put("observations", peer.observations());
            basis.put("peer", p);
        } else {
            basis.put("peer", null);
        }

        Map<String, Object> blend = new LinkedHashMap<>();
        blend.put("benchmarkWeight", benchmarkWeight);
        blend.put("anchorWeight", BigDecimal.ONE.subtract(benchmarkWeight));
        blend.put("anchor", anchor);
        basis.put("blend", blend);
        basis.put("anchor", anchor);
        basis.put("floor", PricingMath.round2(floor));
        basis.put("ceiling", PricingMath.round2(ceiling));
        basis.put("raw", PricingMath.round2(raw));
        basis.put("suggested", suggested);
        basis.put("suggestedMarginPct", marginPct);
        basis.put("text", text(in, c, atMargin, tm, benchmark, ri, cd, pct90, competitor, peer, suggested, marginPct));
        return basis;
    }

    private static String text(Inputs in, BigDecimal c, BigDecimal atMargin, BigDecimal tm,
            Reference.Benchmark benchmark, BigDecimal ri, BigDecimal cd, BigDecimal pct90, CompetitorSummary competitor,
            PeerSummary peer, BigDecimal suggested, BigDecimal marginPct) {
        String sym = symbol(in.currency());
        StringBuilder text = new StringBuilder();
        text.append("Cost ").append(sym).append(PricingMath.round2(c)).append(" (").append(sourceLabel(in.cost().source()))
                .append(") × ").append(benchmark.category());
        if (benchmark.subcategory() != null && !benchmark.subcategory().isBlank()) {
            text.append(" / ").append(benchmark.subcategory());
        }
        text.append(" benchmark margin ").append(tm.stripTrailingZeros().toPlainString()).append("% = ")
                .append(sym).append(PricingMath.round2(atMargin));
        if (in.store() != null && in.store().rpp() != null) {
            text.append("; ").append(in.store().label()).append(" index ×")
                    .append(ri.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        Reference.Commodity commodity = in.commodity();
        if (commodity != null && !"none".equals(commodity.key()) && pct90.signum() != 0) {
            text.append("; ").append(commodity.key()).append(' ').append(pct90.signum() > 0 ? "+" : "")
                    .append(pct90.stripTrailingZeros().toPlainString()).append("% over 90 days (reference");
            if (commodity.asOf() != null) {
                text.append(", ").append(LONG_DATE.format(commodity.asOf()));
            }
            text.append(") ×").append(cd.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (competitor != null) {
            text.append("; blended 50/50 with the competitor median ").append(sym).append(competitor.median())
                    .append(" (").append(competitor.observations()).append(competitor.observations() == 1
                            ? " observation" : " observations").append(competitor.regionMatched() ? ", region-matched" : "")
                    .append(')');
        } else if (peer != null) {
            text.append("; blended 60/40 with your own median ").append(sym).append(peer.median())
                    .append(" (").append(peer.observations()).append(" invoice lines, all branches)");
        }
        text.append("; rounded to ").append(sym).append(suggested);
        if (marginPct != null) {
            text.append(" (").append(marginPct.toPlainString()).append("% margin)");
        }
        text.append('.');
        return text.toString();
    }

    static String sourceLabel(String source) {
        if (source == null) {
            return "unknown";
        }
        return switch (source) {
            case Resolved.PRICE_LIST -> "price list";
            case Resolved.PURCHASES_90D -> "purchases, 90 days";
            case Resolved.SALES_COST -> "sales cost";
            case Resolved.SUPPLIER_LIST -> "supplier list";
            default -> source;
        };
    }

    static String symbol(String currency) {
        if (currency == null) {
            return "$";
        }
        return switch (currency) {
            case "USD", "CAD", "AUD", "MXN" -> "$";
            case "GBP" -> "£";
            case "EUR" -> "€";
            case "INR" -> "₹";
            case "JPY", "CNY" -> "¥";
            default -> currency + " ";
        };
    }

    // ---- the row -------------------------------------------------------------------------

    record CompetitorSummary(BigDecimal median, BigDecimal low, BigDecimal high, int observations,
            boolean regionMatched) {
    }

    record PeerSummary(BigDecimal median, long observations) {
    }

    record BenchmarkSummary(BigDecimal targetMarginPct, BigDecimal lowMarginPct, BigDecimal highMarginPct,
            BigDecimal regionalIndex, BigDecimal commodityDriftPct, BigDecimal price, String note, String matched) {
    }

    /** One item's suggestion as the wizard renders it. {@code basis} is what is stored on apply. */
    record SuggestionRow(String item, String description, String category, String subcategory, String unit,
            String commodity, BigDecimal cost, String costSource, BigDecimal currentPrice, String currentPriceSource,
            String anchor, BigDecimal anchorPrice, CompetitorSummary competitor, PeerSummary peer,
            BenchmarkSummary benchmark, BigDecimal suggestedPrice, BigDecimal suggestedMarginPct,
            BigDecimal floorPrice, BigDecimal ceilingPrice, Map<String, Object> basis, String status,
            boolean unpriceable, String reason) {
    }
}

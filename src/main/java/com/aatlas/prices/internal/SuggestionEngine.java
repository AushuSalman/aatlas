package com.aatlas.prices.internal;

import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PricingModel;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.Stats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Set your prices": one suggested list price per item (spec A §4), with the basis spelled
 * out so a prospect can read where the number came from and the UI can recompute it when a
 * slider moves. Pure: everything it needs is handed in.
 *
 * <p>The tenant's pricing model ({@link PricingModel.Config}) says which steps run. With
 * every toggle on and every number at its default the maths is the wizard's original chain
 * exactly, with one exception: when several competitors agree with each other but sit far
 * from the item's own price (the <b>market gap</b>, {@link PricingModel#COMPETITORS_MARKET_GAP}),
 * the row is priced to that market rather than blended, banded and move-capped toward a cost
 * formula that never knew the market was there. Each step the wizard consults is reported
 * under {@code basis.model} as {@code applied}, {@code skipped} (on, but nothing to work
 * with) or {@code off}.
 *
 * <p><b>The live margin recompute.</b> {@code cost} is the cost the suggestion was priced
 * from and {@code suggestedMarginPct} describes the suggestion alone. When the user edits a
 * price, the page recomputes the margin from {@code cost} and the edited price
 * ({@code (price − cost) ÷ price}); nothing in the model touches {@code cost}, so an edited
 * row's margin is the same arithmetic whatever the model says.
 */
final class SuggestionEngine {

    static final String STATUS_OK = "ok";
    static final String STATUS_NEEDS_COST = "needs-cost";
    static final String REASON_NO_COST = "Add a cost, a competitor price or sales history to get a suggestion";

    static final BigDecimal BENCHMARK_WEIGHT_WITH_COMPETITOR = new BigDecimal("0.5");
    static final BigDecimal BENCHMARK_WEIGHT_WITH_PEER = new BigDecimal("0.6");
    /**
     * The documented default of {@link PricingModel#LOCAL_MARKET_WEIGHT} (55%). What runs is
     * the tenant's number, through {@link #regionalIndex}.
     */
    static final BigDecimal RPP_SENSITIVITY = new BigDecimal("0.55");

    /** Step statuses: the same three words as {@link PricingMath.Step}. */
    static final String APPLIED = "applied";
    static final String SKIPPED = "skipped";
    static final String OFF = "off";

    /** The model steps the wizard consults, in the order {@code basis.model.steps} lists them. */
    static final List<String> MODEL_STEPS = List.of(PricingModel.COMPETITORS, PricingModel.COMPETITORS_MARKET_GAP,
            PricingModel.BLEND_OWN_PRICE, PricingModel.LOCAL_MARKET, PricingModel.COMMODITY, PricingModel.LEARNING,
            PricingModel.CEILING_PLAUSIBILITY, PricingModel.MOVE_CAP, PricingModel.ROUNDING);

    /**
     * How far over the competitor median the ceiling is lifted on a market-gap row, so the
     * market can be reached: the category band and the cost multiple describe the tenant's
     * own margins, not a market three floors up.
     */
    static final BigDecimal MARKET_REACH = new BigDecimal("1.10");

    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
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
     * @param config the tenant's pricing model; null reads as the defaults
     * @param learning the lean from the tenant's own decisions, null when none was read
     */
    record Inputs(Catalogue.ProductRef product, Catalogue.StoreRef store, Resolved cost, Resolved currentPrice,
            List<CompetitorPrices.Observation> competitorObservations, SalesHistory.PriceBand band,
            Reference.Benchmark benchmark, BigDecimal targetMarginOverride, Reference.Commodity commodity,
            String currency, PricingModel.Config config, DecisionPatterns.Learning learning) {

        Inputs {
            config = config == null ? PricingModel.Config.defaults() : config;
        }
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
        PricingModel.Config cfg = in.config();
        Run r = new Run();
        r.sym = symbol(in.currency());
        r.c = in.cost().value();
        r.benchmark = in.benchmark();
        r.tm = in.targetMarginOverride() != null ? in.targetMarginOverride() : r.benchmark.targetMarginPct();
        BigDecimal lo = PricingMath.min(r.benchmark.lowMarginPct(), r.tm);
        BigDecimal hi = PricingMath.max(r.benchmark.highMarginPct(), r.tm);
        r.floor = PricingMath.priceAtMargin(r.c, lo);
        r.ceiling = PricingMath.priceAtMargin(r.c, hi);

        // 1. regional index, store variant only
        Catalogue.StoreRef store = in.store();
        if (!cfg.on(PricingModel.LOCAL_MARKET)) {
            r.steps.add(PricingModel.LOCAL_MARKET, "Local market", OFF, "Off.");
        } else if (store == null || store.rpp() == null) {
            r.steps.add(PricingModel.LOCAL_MARKET, "Local market", SKIPPED,
                    store == null ? "Tenant-wide, so no regional index." : "This branch is priced nationally.");
        } else {
            r.ri = regionalIndex(store, cfg);
            r.regionApplied = true;
            r.steps.add(PricingModel.LOCAL_MARKET, "Local market", APPLIED, "Regional price parity "
                    + plain(store.rpp()) + " at " + plain(cfg.value(PricingModel.LOCAL_MARKET_WEIGHT))
                    + "% weight → ×" + r.ri.setScale(3, RoundingMode.HALF_UP).toPlainString() + ".");
        }

        // 2. commodity drift, reference
        Reference.Commodity commodity = in.commodity();
        boolean linked = commodity != null && !"none".equals(commodity.key());
        r.pct90 = commodity == null || commodity.pct90() == null ? BigDecimal.ZERO : commodity.pct90();
        if (!cfg.on(PricingModel.COMMODITY)) {
            r.steps.add(PricingModel.COMMODITY, "Commodity", OFF, "Off.");
        } else if (!linked) {
            r.steps.add(PricingModel.COMMODITY, "Commodity", SKIPPED, "No commodity linked to this item.");
        } else {
            BigDecimal share = cfg.value(PricingModel.COMMODITY_PASS_THROUGH);
            r.cd = BigDecimal.ONE.add(r.pct90.multiply(share)
                    .divide(TEN_THOUSAND, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP));
            r.commodityApplied = true;
            r.steps.add(PricingModel.COMMODITY, "Commodity", APPLIED, commodity.key() + " moved " + signed(r.pct90)
                    + "% over ninety days; " + plain(share) + "% of that reaches the price (×"
                    + r.cd.setScale(3, RoundingMode.HALF_UP).toPlainString() + ").");
        }

        // 3. benchmark price with the multipliers inside (the wizard has no chain)
        r.atMargin = PricingMath.priceAtMargin(r.c, r.tm);
        r.b = PricingMath.money(r.atMargin.multiply(r.ri).multiply(r.cd));

        // 4. anchors. Several competitors that agree with each other are a market and are never
        // dropped for being far away; only a lone price, or a set that disagrees with itself, faces
        // the plausibility gate.
        r.observed = competitor(in.competitorObservations());
        int minAgreeing = cfg.value(PricingModel.COMPETITORS_MARKET_GAP_MIN_AGREEING).intValue();
        r.credible = cfg.on(PricingModel.COMPETITORS_MARKET_GAP) && r.observed != null
                && credible(r.observed, minAgreeing, cfg.value(PricingModel.COMPETITORS_MARKET_GAP_AGREEMENT));
        if (!cfg.on(PricingModel.COMPETITORS)) {
            r.steps.add(PricingModel.COMPETITORS, "Competitor prices", OFF, r.observed == null ? "Off."
                    : "Off; " + observations(r.observed.observations()) + " on file not used.");
        } else if (r.observed == null) {
            r.steps.add(PricingModel.COMPETITORS, "Competitor prices", SKIPPED,
                    "No competitor price on file for this item.");
        } else if (!r.credible && cfg.on(PricingModel.COMPETITORS_PLAUSIBILITY)
                && implausible(r.observed.median(), current != null ? current : r.floor)) {
            // Against today's price when the item has one (what it really sells for); only an item with
            // no price yet is judged against the minimum-margin price.
            r.steps.add(PricingModel.COMPETITORS, "Competitor prices", SKIPPED, "Competitor median "
                    + r.money(r.observed.median()) + " is under half or over twice "
                    + (current != null ? "today's price " + r.money(current) : "the minimum-margin price " + r.money(r.floor))
                    + ": too far from it to price against, so it was not used.");
        } else {
            r.competitor = r.observed;
            r.steps.add(PricingModel.COMPETITORS, "Competitor prices", APPLIED, "Median " + r.money(r.competitor.median())
                    + " over " + observations(r.competitor.observations())
                    + (r.competitor.regionMatched() ? ", region-matched" : "")
                    + (r.credible && r.competitor.observations() > 1 ? "; they agree with each other (from "
                            + r.money(r.competitor.low()) + " to " + r.money(r.competitor.high())
                            + "), so they count as a market" : "") + ".");
        }
        r.peer = in.band() == null || in.band().median() == null ? null
                : new PeerSummary(PricingMath.round2(in.band().median()), in.band().n());

        // 4b. the market gap: a credible set far from the price it is judged against (today's price,
        // else the benchmark) is a market the item is mispriced against, and leads on its own.
        if (r.competitor != null && r.credible) {
            r.gapRef = current != null && current.signum() > 0 ? current : r.b;
            BigDecimal ratio = r.competitor.median().divide(r.gapRef, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            r.gapAbove = ratio.signum() > 0;
            r.gapPct = ratio.abs().multiply(PricingMath.HUNDRED);
            r.marketGap = r.gapPct.compareTo(cfg.value(PricingModel.COMPETITORS_MARKET_GAP_THRESHOLD)) > 0;
        }

        // 5. blend
        boolean blendOn = cfg.on(PricingModel.BLEND_OWN_PRICE);
        BigDecimal raw;
        if (r.marketGap) {
            // The benchmark is a cost formula, not a market: nothing to average it with.
            r.undercut = cfg.value(PricingModel.COMPETITORS_MARKET_GAP_UNDERCUT);
            r.benchmarkWeight = BigDecimal.ZERO;
            raw = r.competitor.median().multiply(BigDecimal.ONE.subtract(
                    r.undercut.divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP)));
            r.anchor = Anchor.COMPETITOR;
            r.anchorPrice = r.competitor.median();
            r.steps.add(PricingModel.BLEND_OWN_PRICE, "Blend", blendOn ? SKIPPED : OFF, (blendOn ? "Market gap: "
                    : "Off; market gap: ") + "the competitor median " + r.money(r.competitor.median())
                    + " leads on its own, " + undercut(r.undercut) + "; the benchmark " + r.money(r.b)
                    + " is a cost formula, not a market, so it is not blended in.");
        } else if (blendOn && r.competitor != null) {
            r.benchmarkWeight = BENCHMARK_WEIGHT_WITH_COMPETITOR;
            raw = r.b.multiply(r.benchmarkWeight)
                    .add(r.competitor.median().multiply(BigDecimal.ONE.subtract(r.benchmarkWeight)));
            r.anchor = Anchor.COMPETITOR;
            r.anchorPrice = r.competitor.median();
            r.steps.add(PricingModel.BLEND_OWN_PRICE, "Blend", APPLIED, "Benchmark " + r.money(r.b)
                    + " × 50% + competitor median " + r.money(r.competitor.median()) + " × 50%.");
        } else if (blendOn && r.peer != null) {
            r.benchmarkWeight = BENCHMARK_WEIGHT_WITH_PEER;
            raw = r.b.multiply(r.benchmarkWeight).add(r.peer.median().multiply(BigDecimal.ONE.subtract(r.benchmarkWeight)));
            r.anchor = Anchor.PEER;
            r.anchorPrice = r.peer.median();
            r.steps.add(PricingModel.BLEND_OWN_PRICE, "Blend", APPLIED, "Benchmark " + r.money(r.b)
                    + " × 60% + your own median " + r.money(r.peer.median()) + " × 40%.");
        } else {
            r.benchmarkWeight = BigDecimal.ONE;
            raw = r.b;
            r.anchor = Anchor.BENCHMARK;
            r.anchorPrice = r.b;
            r.steps.add(PricingModel.BLEND_OWN_PRICE, "Blend", blendOn ? SKIPPED : OFF, blendOn
                    ? "No competitor or own median to blend with; the benchmark price alone."
                    : "Off; the benchmark price alone.");
        }
        r.raw = PricingMath.money(raw);

        // 6. the lean from the tenant's own decisions, before the band
        DecisionPatterns.Learning learning = in.learning();
        if (!cfg.on(PricingModel.LEARNING)) {
            r.steps.add(PricingModel.LEARNING, "Your decisions", OFF, "Off.");
        } else if (learning == null || !learning.available()) {
            r.steps.add(PricingModel.LEARNING, "Your decisions", SKIPPED,
                    learning == null ? "No decisions recorded yet." : learning.note());
        } else {
            r.leaned = learning;
            BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(learning.movePct())
                    .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP));
            r.raw = PricingMath.money(r.raw.multiply(factor));
            r.steps.add(PricingModel.LEARNING, "Your decisions", APPLIED, learning.note());
        }

        // 7. plausibility cap on the ceiling. On a market-gap row the ceiling is lifted to reach the
        // market instead: the cost multiple, like the band, describes the tenant's margins, not a
        // market that sits far above them.
        if (r.marketGap) {
            BigDecimal reach = PricingMath.money(r.competitor.median().multiply(MARKET_REACH));
            if (r.ceiling == null || reach.compareTo(r.ceiling) > 0) {
                r.ceilingLifted = true;
                r.ceiling = reach;
            }
            boolean on = cfg.on(PricingModel.CEILING_PLAUSIBILITY);
            r.steps.add(PricingModel.CEILING_PLAUSIBILITY, "Ceiling plausibility", on ? SKIPPED : OFF, on
                    ? "Market gap: the cost multiple is not applied, so the price can reach the market"
                            + (r.ceilingLifted ? "; ceiling lifted to " + r.money(r.ceiling) : "") + "."
                    : "Off." + (r.ceilingLifted ? " Market gap: ceiling lifted to " + r.money(r.ceiling) + "." : ""));
        } else if (!cfg.on(PricingModel.CEILING_PLAUSIBILITY)) {
            r.steps.add(PricingModel.CEILING_PLAUSIBILITY, "Ceiling plausibility", OFF, "Off.");
        } else {
            BigDecimal multiple = cfg.value(PricingModel.CEILING_COST_MULTIPLE);
            BigDecimal cap = PricingMath.money(r.c.multiply(multiple));
            if (r.ceiling == null || cap.compareTo(r.ceiling) < 0) {
                r.ceilingCap = cap;
                r.ceiling = PricingMath.max(cap, r.floor);
                r.steps.add(PricingModel.CEILING_PLAUSIBILITY, "Ceiling plausibility", APPLIED, "Ceiling capped at "
                        + plain(multiple) + "× cost (" + r.money(cap) + ")"
                        + (r.ceiling.compareTo(cap) > 0 ? ", held at the floor " + r.money(r.floor) : "") + ".");
            } else {
                r.steps.add(PricingModel.CEILING_PLAUSIBILITY, "Ceiling plausibility", APPLIED, "Ceiling "
                        + r.money(r.ceiling) + " is within " + plain(multiple) + "× cost (" + r.money(cap) + ").");
            }
        }

        // 8. the band, then the move cap - never under the floor or over the ceiling. A market-gap
        // row still holds at the floor (the minimum margin is the tenant's guardrail, whatever the
        // market does) but is not held to today's price: the whole point is to reach the market.
        BigDecimal clamped = PricingMath.clamp(r.raw, r.floor, r.ceiling);
        if (r.marketGap) {
            r.floorHeld = r.floor != null && r.raw.compareTo(r.floor) < 0;
            r.ceilingHeld = !r.floorHeld && r.ceiling != null && r.raw.compareTo(r.ceiling) > 0;
        }
        BigDecimal roundLo = r.floor;
        BigDecimal roundHi = r.ceiling;
        r.current = current;
        if (r.marketGap) {
            boolean on = cfg.on(PricingModel.MOVE_CAP);
            r.steps.add(PricingModel.MOVE_CAP, "Move cap", on ? SKIPPED : OFF, on
                    ? "Market gap: the cap is lifted so the price can reach the market." : "Off.");
        } else if (!cfg.on(PricingModel.MOVE_CAP)) {
            r.steps.add(PricingModel.MOVE_CAP, "Move cap", OFF, "Off.");
        } else if (current == null || current.signum() <= 0) {
            r.steps.add(PricingModel.MOVE_CAP, "Move cap", SKIPPED, "No price today, so nothing to hold to.");
        } else {
            BigDecimal pct = cfg.value(PricingModel.MOVE_CAP_MAX_PCT);
            BigDecimal share = pct.divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP);
            BigDecimal capLo = PricingMath.money(current.multiply(BigDecimal.ONE.subtract(share)));
            BigDecimal capHi = PricingMath.money(current.multiply(BigDecimal.ONE.add(share)));
            BigDecimal held = PricingMath.clamp(clamped, capLo, capHi);
            BigDecimal legal = PricingMath.clamp(held, r.floor, r.ceiling);
            String within = "±" + plain(pct) + "% of today's " + r.money(current);
            String note;
            if (held.compareTo(clamped) == 0) {
                note = "Within " + within + "; nothing to hold.";
            } else if (legal.compareTo(held) == 0) {
                r.moveCapBound = true;
                note = "Held within " + within + ", to " + r.money(legal) + ".";
            } else if (legal.compareTo(clamped) == 0) {
                note = "The " + within + " cap would leave the band; the floor and ceiling win.";
            } else {
                r.moveCapBound = true;
                note = "Held within " + within + ", then " + (legal.compareTo(held) < 0
                        ? "brought under the ceiling " : "lifted to the floor ") + r.money(legal) + ".";
            }
            clamped = legal;
            BigDecimal tightLo = PricingMath.max(r.floor, capLo);
            BigDecimal tightHi = PricingMath.min(r.ceiling, capHi);
            if (tightLo != null && tightHi != null && tightLo.compareTo(tightHi) <= 0) {
                roundLo = tightLo;
                roundHi = tightHi;
            }
            r.steps.add(PricingModel.MOVE_CAP, "Move cap", APPLIED, note);
        }

        // 9. rounding
        if (cfg.on(PricingModel.ROUNDING)) {
            r.rounded = true;
            r.suggested = roundInside(clamped, roundLo, roundHi);
            r.steps.add(PricingModel.ROUNDING, "Rounded", APPLIED, "To retail price points, inside the band.");
        } else {
            r.suggested = PricingMath.round2(clamped);
            r.steps.add(PricingModel.ROUNDING, "Rounded", OFF, "To the cent only.");
        }
        BigDecimal marginPct = PricingMath.marginPct(r.suggested, r.c);
        r.marginPct = marginPct == null ? null : marginPct.setScale(1, RoundingMode.HALF_UP);
        marketGapStep(cfg, r, current, minAgreeing);

        BenchmarkSummary benchmarkSummary = new BenchmarkSummary(r.tm, r.benchmark.lowMarginPct(),
                r.benchmark.highMarginPct(), r.ri.setScale(3, RoundingMode.HALF_UP), r.pct90, PricingMath.round2(r.b),
                r.benchmark.note(), r.benchmark.matched());

        Map<String, Object> basis = basis(in, cfg, r);

        return new SuggestionRow(product.itemNumber(), product.description(), product.category(),
                product.subcategory(), product.unit(), product.commodity(), PricingMath.round2(r.c),
                in.cost().source(), current, currentSource, r.anchor, PricingMath.round2(r.anchorPrice), r.observed,
                r.peer, benchmarkSummary, r.suggested, r.marginPct, PricingMath.round2(r.floor),
                PricingMath.round2(r.ceiling), basis, STATUS_OK, false, null);
    }

    /**
     * The multiplier a branch's regional price parity applies to the benchmark price:
     * {@code 1 − (rpp − 100)/100 × weight}, the weight being the model's
     * {@link PricingModel#LOCAL_MARKET_WEIGHT} ÷ 100. Exactly 1 with the step off, without a
     * branch or with a branch priced nationally - the one place the wizard and its response
     * header read it from.
     */
    static BigDecimal regionalIndex(Catalogue.StoreRef store, PricingModel.Config cfg) {
        PricingModel.Config config = cfg == null ? PricingModel.Config.defaults() : cfg;
        if (store == null || store.rpp() == null || !config.on(PricingModel.LOCAL_MARKET)) {
            return BigDecimal.ONE.setScale(PricingMath.RATIO_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal weight = config.value(PricingModel.LOCAL_MARKET_WEIGHT)
                .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.ONE.subtract(store.rpp().subtract(PricingMath.HUNDRED)
                .divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE, RoundingMode.HALF_UP)
                .multiply(weight)).setScale(PricingMath.RATIO_SCALE, RoundingMode.HALF_UP);
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

    /** Under half or over twice the minimum-margin price: a different product or unit, not a market. */
    static boolean implausible(BigDecimal median, BigDecimal floor) {
        if (median == null || floor == null || floor.signum() <= 0) {
            return false;
        }
        return median.multiply(TWO).compareTo(floor) < 0 || median.compareTo(floor.multiply(TWO)) > 0;
    }

    /**
     * A competitor set is credible - a market rather than a wrong match - when at least
     * {@code minAgreeing} prices agree with each other: the highest no more than
     * {@code agreementPct} above the lowest. A credible set is never dropped for being far from
     * the item's own price; that distance is the market gap.
     */
    static boolean credible(CompetitorSummary summary, int minAgreeing, BigDecimal agreementPct) {
        if (summary == null || summary.observations() < minAgreeing || summary.low() == null
                || summary.high() == null || summary.low().signum() <= 0) {
            return false;
        }
        BigDecimal span = BigDecimal.ONE.add(agreementPct.divide(PricingMath.HUNDRED, PricingMath.RATIO_SCALE,
                RoundingMode.HALF_UP));
        return summary.high().compareTo(summary.low().multiply(span)) <= 0;
    }

    /** The market-gap step as it ran, written once the floor and ceiling have had their say. */
    private static void marketGapStep(PricingModel.Config cfg, Run r, BigDecimal current, int minAgreeing) {
        String key = PricingModel.COMPETITORS_MARKET_GAP;
        String label = "Market gap";
        if (!cfg.on(key)) {
            r.steps.add(key, label, OFF, "Off.");
            return;
        }
        CompetitorSummary c = r.observed;
        if (c == null) {
            r.steps.add(key, label, SKIPPED, "No competitor price on file.");
        } else if (c.observations() < minAgreeing) {
            r.steps.add(key, label, SKIPPED, c.observations() == 1 ? "Only one competitor price."
                    : "Only " + c.observations() + " competitor prices; " + minAgreeing + " must agree.");
        } else if (!r.credible) {
            r.steps.add(key, label, SKIPPED, "Competitor prices disagree with each other, from " + r.money(c.low())
                    + " to " + r.money(c.high()) + ".");
        } else if (r.competitor == null) {
            r.steps.add(key, label, SKIPPED, "Competitor prices not used.");
        } else if (!r.marketGap) {
            r.steps.add(key, label, SKIPPED, "Competitors agree with your price: median " + r.money(c.median()) + ", "
                    + pct(r.gapPct) + " from " + (current != null && current.signum() > 0 ? "today's "
                    : "the benchmark price ") + r.money(r.gapRef) + ".");
        } else {
            StringBuilder note = new StringBuilder(agreeing(r, c)).append(" (from ").append(r.money(c.low()))
                    .append(" to ").append(r.money(c.high())).append("); ")
                    .append(current != null && current.signum() > 0 ? "your price today is " : "the benchmark price is ")
                    .append(r.money(r.gapRef));
            if (r.gapAbove) {
                note.append(", ").append(pct(r.gapPct)).append(" under.");
            } else {
                note.append(", and the market is ").append(pct(r.gapPct)).append(" under it.");
            }
            note.append(" The suggestion follows the market, ").append(undercut(r.undercut));
            if (r.floorHeld) {
                note.append(", held at the floor ").append(r.money(r.floor)).append(" (your minimum margin)");
            } else if (r.ceilingHeld) {
                note.append(", held under the ceiling ").append(r.money(r.ceiling));
            }
            r.steps.add(key, label, APPLIED, note.append('.').toString());
        }
    }

    /** "3 competitors agree at around $70.00", or "1 competitor at $70.00" when the tenant asks for one. */
    private static String agreeing(Run r, CompetitorSummary c) {
        return c.observations() == 1 ? "1 competitor at " + r.money(c.median())
                : c.observations() + " competitors agree at around " + r.money(c.median());
    }

    /** "3% under its median", or "at its median" with no undercut. */
    private static String undercut(BigDecimal undercutPct) {
        return undercutPct == null || undercutPct.signum() == 0 ? "at its median"
                : plain(undercutPct) + "% under its median";
    }

    /** A whole-number percentage with a thousands separator: 2,233%. */
    private static String pct(BigDecimal pct) {
        return String.format(Locale.ENGLISH, "%,d", pct.setScale(0, RoundingMode.HALF_UP).longValue()) + "%";
    }

    /** Rounds to the price step, then back inside {@code [lo, hi]} if the rounding stepped out. */
    static BigDecimal roundInside(BigDecimal price, BigDecimal lo, BigDecimal hi) {
        BigDecimal rounded = PricingMath.roundPricePoint(price);
        if (hi != null && rounded.compareTo(hi) > 0) {
            rounded = PricingMath.roundPricePoint(price, RoundingMode.FLOOR);
        }
        if (lo != null && rounded.compareTo(lo) < 0) {
            rounded = PricingMath.roundPricePoint(price, RoundingMode.CEILING);
        }
        if ((hi != null && rounded.compareTo(hi) > 0) || (lo != null && rounded.compareTo(lo) < 0)) {
            rounded = PricingMath.round2(PricingMath.clamp(price, lo, hi));
        }
        return rounded;
    }

    private static Map<String, Object> basis(Inputs in, PricingModel.Config cfg, Run r) {
        Map<String, Object> basis = new LinkedHashMap<>();
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("value", PricingMath.round2(r.c));
        cost.put("source", in.cost().source());
        cost.put("asOf", in.cost().asOf() == null ? null : in.cost().asOf().toString());
        basis.put("cost", cost);

        Map<String, Object> bench = new LinkedHashMap<>();
        bench.put("category", r.benchmark.category());
        bench.put("subcategory", r.benchmark.subcategory());
        bench.put("targetMarginPct", r.tm);
        bench.put("lowMarginPct", r.benchmark.lowMarginPct());
        bench.put("highMarginPct", r.benchmark.highMarginPct());
        bench.put("price", PricingMath.round2(r.atMargin));
        bench.put("note", r.benchmark.note());
        bench.put("matched", r.benchmark.matched());
        bench.put("overridden", in.targetMarginOverride() != null);
        basis.put("benchmark", bench);

        Catalogue.StoreRef store = in.store();
        if (store != null && store.rpp() != null) {
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("store", store.storeCode());
            region.put("rpp", store.rpp());
            region.put("multiplier", r.ri.setScale(3, RoundingMode.HALF_UP));
            basis.put("regionalIndex", region);
        } else {
            basis.put("regionalIndex", null);
        }

        Reference.Commodity commodity = in.commodity();
        if (commodity != null && !"none".equals(commodity.key())) {
            Map<String, Object> com = new LinkedHashMap<>();
            com.put("key", commodity.key());
            com.put("pct90", r.pct90);
            com.put("asOf", commodity.asOf() == null ? null : commodity.asOf().toString());
            com.put("multiplier", r.cd.setScale(3, RoundingMode.HALF_UP));
            com.put("source", commodity.provenance());
            basis.put("commodity", com);
        } else {
            basis.put("commodity", null);
        }

        // What the number used: an observed competitor the model ignored is on the row, not here.
        if (r.competitor != null) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("median", r.competitor.median());
            comp.put("low", r.competitor.low());
            comp.put("high", r.competitor.high());
            comp.put("observations", r.competitor.observations());
            comp.put("regionMatched", r.competitor.regionMatched());
            basis.put("competitor", comp);
        } else {
            basis.put("competitor", null);
        }
        // The market the row was priced to, when credible competitors sat far from its own price.
        if (r.marketGap) {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("median", r.competitor.median());
            gap.put("low", r.competitor.low());
            gap.put("high", r.competitor.high());
            gap.put("observations", r.competitor.observations());
            gap.put("gapPct", r.gapPct.setScale(1, RoundingMode.HALF_UP));
            gap.put("direction", r.gapAbove ? "above" : "below");
            basis.put("marketGap", gap);
        } else {
            basis.put("marketGap", null);
        }
        if (r.peer != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("median", r.peer.median());
            p.put("observations", r.peer.observations());
            basis.put("peer", p);
        } else {
            basis.put("peer", null);
        }

        Map<String, Object> blend = new LinkedHashMap<>();
        blend.put("benchmarkWeight", r.benchmarkWeight);
        blend.put("anchorWeight", BigDecimal.ONE.subtract(r.benchmarkWeight));
        blend.put("anchor", r.anchor);
        basis.put("blend", blend);
        basis.put("anchor", r.anchor);
        basis.put("floor", PricingMath.round2(r.floor));
        basis.put("ceiling", PricingMath.round2(r.ceiling));
        basis.put("raw", PricingMath.round2(r.raw));
        basis.put("suggested", r.suggested);
        basis.put("suggestedMarginPct", r.marginPct);

        Map<String, Object> model = new LinkedHashMap<>();
        int[] toggles = cfg.toggleCount();
        model.put("on", toggles[0]);
        model.put("total", toggles[1]);
        model.put("steps", r.steps.ordered());
        basis.put("model", model);

        basis.put("text", text(in, r));
        return basis;
    }

    private static String text(Inputs in, Run r) {
        String sym = r.sym;
        StringBuilder text = new StringBuilder();
        text.append("Cost ").append(sym).append(PricingMath.round2(r.c)).append(" (").append(sourceLabel(in.cost().source()))
                .append(") × ").append(r.benchmark.category());
        if (r.benchmark.subcategory() != null && !r.benchmark.subcategory().isBlank()) {
            text.append(" / ").append(r.benchmark.subcategory());
        }
        text.append(" benchmark margin ").append(plain(r.tm)).append("% = ")
                .append(sym).append(PricingMath.round2(r.atMargin));
        if (r.regionApplied) {
            text.append("; ").append(in.store().label()).append(" index ×")
                    .append(r.ri.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        Reference.Commodity commodity = in.commodity();
        if (r.commodityApplied && r.pct90.signum() != 0) {
            text.append("; ").append(commodity.key()).append(' ').append(signed(r.pct90))
                    .append("% over 90 days (").append(commodity.provenance());
            if (commodity.asOf() != null) {
                text.append(", ").append(LONG_DATE.format(commodity.asOf()));
            }
            text.append(") ×").append(r.cd.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (r.marketGap) {
            text.append("; market gap: ").append(agreeingMoney(r)).append(r.gapAbove ? ", far above " : ", far below ")
                    .append(r.current != null && r.current.signum() > 0 ? "your " : "the benchmark ")
                    .append(r.money(r.gapRef)).append(" — priced to the market, ")
                    .append(r.undercut == null || r.undercut.signum() == 0 ? "at its median"
                            : plain(r.undercut) + "% under");
            if (r.floorHeld) {
                text.append(", held at the floor ").append(r.money(r.floor));
            } else if (r.ceilingHeld) {
                text.append(", held under the ceiling ").append(r.money(r.ceiling));
            }
        } else if (Anchor.COMPETITOR.equals(r.anchor)) {
            text.append("; blended 50/50 with the competitor median ").append(sym).append(r.competitor.median())
                    .append(" (").append(observations(r.competitor.observations()))
                    .append(r.competitor.regionMatched() ? ", region-matched" : "").append(')');
        } else if (Anchor.PEER.equals(r.anchor)) {
            text.append("; blended 60/40 with your own median ").append(sym).append(r.peer.median())
                    .append(" (").append(r.peer.observations()).append(" invoice lines, all branches)");
        } else if (r.competitor != null || r.peer != null) {
            text.append("; the benchmark price alone, the blend being off");
        }
        if (r.leaned != null) {
            text.append("; leaning ").append(signed(BigDecimal.valueOf(r.leaned.movePct())))
                    .append("% from your past decisions");
        }
        if (r.ceilingCap != null) {
            text.append("; ceiling held to ").append(sym).append(PricingMath.round2(r.ceiling))
                    .append(" by the cost multiple");
        }
        if (r.moveCapBound) {
            text.append("; held within the move cap of today's ").append(sym).append(r.current);
        }
        text.append(r.rounded ? "; rounded to " : "; to the cent, ").append(sym).append(r.suggested);
        if (r.marginPct != null) {
            text.append(" (").append(r.marginPct.toPlainString()).append("% margin)");
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

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + plain(v);
    }

    private static String observations(int n) {
        return n + (n == 1 ? " observation" : " observations");
    }

    /** "3 competitors agree at $70.00" with the currency symbol, for the sentence. */
    private static String agreeingMoney(Run r) {
        int n = r.competitor.observations();
        return n == 1 ? "1 competitor at " + r.money(r.competitor.median())
                : n + " competitors agree at " + r.money(r.competitor.median());
    }

    // ---- the chain as it ran -------------------------------------------------------------

    /** Every figure the chain produced, so the basis and the sentence read one set. */
    private static final class Run {
        String sym;
        BigDecimal c;
        BigDecimal tm;
        BigDecimal atMargin;
        Reference.Benchmark benchmark;
        BigDecimal ri = BigDecimal.ONE;
        boolean regionApplied;
        BigDecimal cd = BigDecimal.ONE;
        BigDecimal pct90 = BigDecimal.ZERO;
        boolean commodityApplied;
        BigDecimal b;
        /** What was on file, and what the anchor used (null when off, absent or implausible). */
        CompetitorSummary observed;
        CompetitorSummary competitor;
        /** Enough competitors agreeing with each other to be a market, whatever the distance. */
        boolean credible;
        /** A credible set far from {@link #gapRef}: the row is priced to the market. */
        boolean marketGap;
        /** What the competitor median was judged against: today's price, else the benchmark price. */
        BigDecimal gapRef;
        /** {@code |median / gapRef − 1| × 100}, unrounded; set whenever the set is credible. */
        BigDecimal gapPct;
        boolean gapAbove;
        /** The market-gap undercut, %, when the gap held. */
        BigDecimal undercut;
        boolean ceilingLifted;
        boolean floorHeld;
        boolean ceilingHeld;
        PeerSummary peer;
        BigDecimal benchmarkWeight;
        String anchor;
        BigDecimal anchorPrice;
        DecisionPatterns.Learning leaned;
        BigDecimal floor;
        BigDecimal ceiling;
        /** The cost multiple, when it lowered the ceiling. */
        BigDecimal ceilingCap;
        BigDecimal raw;
        BigDecimal current;
        boolean moveCapBound;
        boolean rounded;
        BigDecimal suggested;
        BigDecimal marginPct;
        final Steps steps = new Steps();

        String money(BigDecimal v) {
            return v == null ? "—" : sym + PricingMath.round2(v).toPlainString();
        }
    }

    /** The model steps as they ran, emitted in {@link #MODEL_STEPS} order. */
    private static final class Steps {
        private final Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();

        void add(String key, String label, String status, String note) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("key", key);
            step.put("label", label);
            step.put("status", status);
            step.put("note", note);
            byKey.put(key, step);
        }

        List<Map<String, Object>> ordered() {
            List<Map<String, Object>> out = new ArrayList<>(MODEL_STEPS.size());
            for (String key : MODEL_STEPS) {
                Map<String, Object> step = byKey.get(key);
                if (step != null) {
                    out.add(step);
                }
            }
            return out;
        }
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

    /**
     * One item's suggestion as the wizard renders it. {@code basis} is what is stored on apply;
     * its {@code model} entry says which model steps were applied, skipped or off.
     *
     * @param cost the cost the suggestion was priced from - the page recomputes an edited
     *             price's margin from it
     * @param competitor what was on file for the item; whether the anchor used it is
     *                   {@code anchor} and {@code basis.model}
     * @param suggestedMarginPct the margin of {@code suggestedPrice} alone; stale once the
     *                           price is edited
     */
    record SuggestionRow(String item, String description, String category, String subcategory, String unit,
            String commodity, BigDecimal cost, String costSource, BigDecimal currentPrice, String currentPriceSource,
            String anchor, BigDecimal anchorPrice, CompetitorSummary competitor, PeerSummary peer,
            BenchmarkSummary benchmark, BigDecimal suggestedPrice, BigDecimal suggestedMarginPct,
            BigDecimal floorPrice, BigDecimal ceilingPrice, Map<String, Object> basis, String status,
            boolean unpriceable, String reason) {
    }
}

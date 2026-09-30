package com.aatlas.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The pricing arithmetic every screen shares: sell, insights and bulk call these so the same
 * inputs give the same number everywhere. Money is {@link BigDecimal}; ratios are primitive
 * doubles; nothing here reads a row or a clock.
 *
 * <p>{@link #div} is the one division: it returns null for a null or zero denominator, so
 * no formula throws and the step that needed the ratio is skipped rather than zeroed.
 */
public final class PricingMath {

    public static final int MONEY_SCALE = 4;
    public static final int RATIO_SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Blend weight of the market anchor against the pair's own reference price, by source. */
    public static final double WEIGHT_COMPETITOR = 0.6;
    public static final double WEIGHT_PEER = 0.5;
    public static final double WEIGHT_BENCHMARK = 0.35;
    public static final double WEIGHT_HISTORY = 0;

    /** Commodity pass-through: a quarter of the 90-day index move reaches the price. */
    public static final double COMMODITY_PASS_THROUGH = 0.25;

    private PricingMath() {
    }

    // ---- arithmetic ----------------------------------------------------------------------

    /** {@code a / b} at {@value #RATIO_SCALE} places; null when either is null or {@code b} is zero. */
    public static BigDecimal div(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) {
            return null;
        }
        return a.divide(b, RATIO_SCALE, ROUNDING);
    }

    /** {@code a / b × 100}, or null. */
    public static BigDecimal pct(BigDecimal a, BigDecimal b) {
        BigDecimal ratio = div(a, b);
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, ROUNDING);
    }

    public static BigDecimal times(BigDecimal value, double factor) {
        return value.multiply(BigDecimal.valueOf(factor)).setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal clamp(BigDecimal value, BigDecimal lo, BigDecimal hi) {
        BigDecimal result = value;
        if (lo != null && result.compareTo(lo) < 0) {
            result = lo;
        }
        if (hi != null && result.compareTo(hi) > 0) {
            result = hi;
        }
        return result;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** {@code cost / (1 − marginPct/100)}: the price that earns a gross margin on a cost. */
    public static BigDecimal priceAtMargin(BigDecimal cost, BigDecimal marginPct) {
        if (cost == null || marginPct == null) {
            return null;
        }
        BigDecimal keep = BigDecimal.ONE.subtract(marginPct.divide(HUNDRED, RATIO_SCALE, ROUNDING));
        if (keep.signum() <= 0) {
            return null;
        }
        return cost.divide(keep, MONEY_SCALE, ROUNDING);
    }

    /** {@code (price − cost) / price × 100}; null without both or with a zero price. */
    public static BigDecimal marginPct(BigDecimal price, BigDecimal cost) {
        if (price == null || cost == null) {
            return null;
        }
        return pct(price.subtract(cost), price);
    }

    // ---- 3.2 demand ----------------------------------------------------------------------

    /**
     * What the trailing 90 days say about demand, in the shape the demand panel renders.
     *
     * @param level {@code high}, {@code medium} or {@code low}
     * @param movePercent the price move demand justifies, already scaled by confidence
     * @param confWeight 0.35-0.95, the weight the recommendation puts on the demand step
     * @param trendPct null when the prior window sold nothing
     */
    public record Demand(String level, String label, double movePercent, double confWeight, double index,
            String confidence, String trendDirection, BigDecimal trendPct, BigDecimal recentVelocity,
            BigDecimal expectedVelocity, double maxAdjustmentPct, int historyDays, long transactions) {

        public static final String HIGH = "high";
        public static final String MEDIUM = "medium";
        public static final String LOW = "low";
    }

    /** Null when there were no transactions in the 180 days: nothing to say. */
    public static Demand demand(SalesHistory.Velocity v) {
        if (v == null) {
            return null;
        }
        long n = v.recentTxns() + v.priorTxns();
        if (n == 0) {
            return null;
        }
        double confWeight = 0.35 + 0.6 * Math.min(1, n / 60.0);
        BigDecimal trend = v.trendPct();
        boolean newAtStore = trend == null && v.recentTxns() > 0;
        double t = trend == null ? 0 : trend.doubleValue();
        double vp = v.volumePercentile() == null ? 0.5 : v.volumePercentile().doubleValue();

        String level;
        String label;
        if (newAtStore) {
            level = Demand.MEDIUM;
            label = "New at this store";
        } else if (t >= 8 || (t >= 0 && vp >= 0.75)) {
            level = Demand.HIGH;
            label = "Demand rising";
        } else if (t <= -8 || (t < 0 && vp <= 0.25)) {
            level = Demand.LOW;
            label = "Demand softening";
        } else {
            level = Demand.MEDIUM;
            label = "Demand steady";
        }
        double move = trend == null ? 0 : Stats.clamp(t * 0.15, -3, 3) * confWeight;
        double index = trend == null ? 100 : 100 + t;
        String confidence = n >= 40 ? "High" : n >= 15 ? "Medium" : "Low";
        String direction = trend == null || Math.abs(t) < 5 ? "flat" : t > 0 ? "up" : "down";
        return new Demand(level, label, move, confWeight, index, confidence, direction, trend,
                v.recentPerWeek(), v.priorPerWeek(), 3, v.historyDays(), n);
    }

    // ---- 3.1 recommendation --------------------------------------------------------------

    /** Chain steps this many points across the corridor when hunting the profit peak. */
    static final int PROFIT_GRID_POINTS = 48;

    /** Gauss-Hermite-like 5-point weights for a normal: at −2sd, −sd, 0, +sd, +2sd. */
    private static final double[] QUAD_OFFSETS = {-2, -1, 0, 1, 2};
    private static final double[] QUAD_WEIGHTS = {0.0540, 0.2442, 0.4036, 0.2442, 0.0540};

    /** Below this the elasticity is a positive or flat slope, which the profit curve cannot use. */
    static final double ELASTICITY_MIN_ABS = 0.1;

    /** The trace key of the final in-band tier-gap fit: not a registry parameter, a step of {@link PricingModel#TIER_GAP}. */
    public static final String TIER_GAP_FIT = "tierGap.fit";

    /** A narrower band than this cannot hold a meaningful pair; the tiers converge (§3 step 10). */
    static final double PARTIAL_GAP_FLOOR = 0.03;

    /**
     * A decision-history track record for the pair being priced.
     *
     * @param priorApplied prices already applied for this item at this branch (the ramp's n)
     * @param learning the lean from past decisions, or null for none
     * @param habit the bulk strategy habit, or null
     */
    public record Track(long priorApplied, DecisionPatterns.Learning learning, DecisionPatterns.Habit habit) {

        public static Track none() {
            return new Track(0, null, null);
        }
    }

    /**
     * Everything the chain reads, resolved by the caller once.
     *
     * @param cost nullable
     * @param currentPrice today's price {@code p0} (the ladder), nullable
     * @param ownRef the pair's own last sale price, nullable
     * @param ladderAnchor the ladder's own anchor, used only as the last rung of the legacy path
     * @param competitorMedian the competitor anchor, nullable; {@code competitorCount} rows behind it
     * @param peerQ2 the median of the other branches' medians; {@code peerStores} of them
     * @param bandQ1/bandQ3 the item's own price quartiles over twelve months; {@code bandN} lines
     * @param demand nullable
     * @param lastSale the newest sale of the pair, for demand staleness; nullable
     * @param rpp regional price parity of the branch, nullable
     * @param elasticity the fitted or default elasticity
     * @param ordersAtStore invoice lines at this branch in twelve months (the segment)
     * @param benchmarkTargetMarginPct the category's target margin, for the benchmark rung
     * @param track the decision-history signals; {@link Track#none()} when the caller has none
     * @param rampSalt what the ramp's wobble hashes: item, branch and month
     */
    public record Inputs(BigDecimal cost, BigDecimal currentPrice, BigDecimal ownRef, Anchor ladderAnchor,
            BigDecimal competitorMedian, int competitorCount, BigDecimal peerQ2, BigDecimal peerQ3, int peerStores,
            BigDecimal bandQ1, BigDecimal bandQ3, long bandN, Demand demand, LocalDate lastSale, LocalDate today,
            BigDecimal commodityPct90, BigDecimal rpp, SalesHistory.Elasticity elasticity, long ordersAtStore,
            BigDecimal benchmarkTargetMarginPct, Reference.Guardrails guardrails, Track track, String rampSalt) {
    }

    /**
     * One step of the chain as it ran: {@code applied}, {@code skipped} (on, but its input was
     * missing) or {@code off} (switched off in the model). {@code win}/{@code profit} are the
     * two tiers after the step.
     */
    public record Step(String key, String label, String status, BigDecimal win, BigDecimal profit, String note) {

        public static final String APPLIED = "applied";
        public static final String SKIPPED = "skipped";
        public static final String OFF = "off";
    }

    /**
     * The chain, one value per step, so a derivation panel can show every multiplier exactly
     * once. A null step value means the step was skipped for want of its input.
     *
     * @param base the win reference after the anchor (and, on the ladder, the own-price blend)
     * @param afterDemand null when demand did not act
     * @param afterCommodity null when the item has no commodity or the step is off
     * @param afterRegion null when no regional index applied
     * @param floor the hard floor: the minimum-margin price, or the own reference without a cost
     * @param targetFloor the corridor floor the targets aim at (never under {@code floor})
     * @param ceilingSource which candidate set the ceiling
     * @param elasticity the sensitivity the chain used, after the prior blend
     * @param elasticityConfidence the measured share of it, 0-1
     * @param segment {@code regular} or {@code occasional}
     * @param headlineTier {@code optimal} or {@code aggressive}
     * @param winTarget/profitTarget where the two tiers are heading before the ramp and the final band
     * @param profitPeak the theoretical profit-max {@code p*}, null when undefined
     * @param maturity how far along the ramp the quoted prices are, 1 with the ramp off
     * @param steps the chain as it ran
     * @param flags {@code constraint_conflict}, {@code tier_gap_infeasible}, {@code competitor_implausible}...
     */
    public record Recommendation(BigDecimal anchor, String anchorSource, double anchorWeight, BigDecimal ownRef,
            BigDecimal base, BigDecimal afterDemand, BigDecimal afterCommodity, BigDecimal afterRegion,
            BigDecimal floor, BigDecimal ceiling, BigDecimal optimal, BigDecimal aggressive,
            BigDecimal targetFloor, String ceilingSource, BigDecimal elasticity, double elasticityConfidence,
            String segment, String headlineTier, BigDecimal winTarget, BigDecimal profitTarget,
            BigDecimal profitPeak, double maturity, double contestedness, String externalRole,
            List<Step> steps, List<String> flags) {

        public static final String FLAG_CONSTRAINT_CONFLICT = "constraint_conflict";
        public static final String FLAG_TIER_GAP_INFEASIBLE = "tier_gap_infeasible";
        public static final String FLAG_COMPETITOR_IMPLAUSIBLE = "competitor_implausible";
        public static final String FLAG_CEILING_AT_FLOOR = "ceiling_at_floor";
    }

    public static double anchorWeight(String source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case Anchor.COMPETITOR -> WEIGHT_COMPETITOR;
            case Anchor.PEER -> WEIGHT_PEER;
            case Anchor.BENCHMARK -> WEIGHT_BENCHMARK;
            default -> WEIGHT_HISTORY;
        };
    }

    /** The two tiers as they move through the chain. */
    private static final class Running {
        BigDecimal win;
        BigDecimal profit;
        final List<Step> steps = new ArrayList<>();
        final List<String> flags = new ArrayList<>();

        void step(String key, String label, String status, String note) {
            steps.add(new Step(key, label, status, round2(win), round2(profit), note));
        }

        void scaleBoth(double factor) {
            win = times(win, factor);
            profit = times(profit, factor);
        }
    }

    /**
     * The chain, as the tenant's model configures it. Empty when neither an anchor nor an own
     * reference exists: not priceable.
     *
     * <p>Order, which is also the order of {@link Recommendation#steps}: sensitivity, segment,
     * competitor gate, anchor (internal baseline or the ladder), corridor, profit-max target,
     * demand, commodity, local market, learning, tier gap, phase-in, final band with the move
     * cap, tier gap fitted inside the band, rounding. The hard floor and the market ceiling
     * bound every price whatever the model says; the target floor only shapes the targets.
     */
    public static Optional<Recommendation> recommend(Inputs in, PricingModel.Config cfg) {
        Reference.Guardrails g = in.guardrails();
        BigDecimal cost = positive(in.cost());
        BigDecimal p0 = positive(in.currentPrice());
        BigDecimal own = positive(in.ownRef());
        Track track = in.track() == null ? Track.none() : in.track();
        Running r = new Running();

        // ---- 1. price sensitivity ----------------------------------------------------------
        double prior = cfg.number(PricingModel.ELASTICITY_PRIOR);
        double wPrior = cfg.number(PricingModel.ELASTICITY_PRIOR_WEIGHT);
        SalesHistory.Elasticity el = in.elasticity();
        boolean measured = cfg.on(PricingModel.ELASTICITY) && el != null && el.coefficient() != null
                && !SalesHistory.Elasticity.DEFAULT.equals(el.basis());
        double e;
        double sd;
        double eConfidence;
        String eNote;
        if (measured) {
            double meas = el.coefficient().doubleValue();
            double se = el.stdError() == null ? Double.NaN : el.stdError().doubleValue();
            double wMeas = se > 0 ? Math.min(1 / (se * se), Math.max(1, el.n())) : Math.max(1, el.n());
            e = (wMeas * meas + wPrior * prior) / (wMeas + wPrior);
            sd = 1 / Math.sqrt(wMeas + wPrior);
            eConfidence = wMeas / (wMeas + wPrior);
            eNote = "Measured " + fmt(meas, 2) + " over " + el.n() + " months (" + el.basis() + "), blended "
                    + Math.round(eConfidence * 100) + "/" + Math.round((1 - eConfidence) * 100)
                    + " with the prior " + fmt(prior, 2) + ".";
        } else {
            e = prior;
            sd = wPrior > 0 ? 1 / Math.sqrt(wPrior) : 0.5;
            eConfidence = 0;
            eNote = cfg.on(PricingModel.ELASTICITY)
                    ? "Too little history to measure; the prior " + fmt(prior, 2) + " stands."
                    : "Measurement is off; the prior " + fmt(prior, 2) + " stands.";
        }
        e = Stats.clamp(e, -3, -ELASTICITY_MIN_ABS);
        double sensitivity = Stats.clamp((0.5 - e) / 3.5, 0, 1);

        // ---- 2. segment ------------------------------------------------------------------
        boolean segmentOn = cfg.on(PricingModel.SEGMENT);
        long regularMin = (long) cfg.number(PricingModel.SEGMENT_REGULAR_MIN_ORDERS);
        String segment = !segmentOn || in.ordersAtStore() >= regularMin ? "regular" : "occasional";
        String headline = "regular".equals(segment) ? "optimal" : "aggressive";

        // ---- 3. hard floor ---------------------------------------------------------------
        BigDecimal hardFloor = cost != null ? priceAtMargin(cost, g.minMarginPct()) : min(own, in.bandQ1());

        // ---- 4. competitors --------------------------------------------------------------
        boolean competitorsOn = cfg.on(PricingModel.COMPETITORS);
        BigDecimal cm = competitorsOn ? positive(in.competitorMedian()) : null;
        int cc = cm == null ? 0 : Math.max(1, in.competitorCount());
        String competitorNote;
        // Plausible against what the item actually sells for: today's price, else the other branches'
        // median, and only with neither the minimum-margin price. Judging it against the margin floor
        // threw out real markets on high-margin lines - a breaker costing $20.67 that sells for $82 had
        // its $82.39 competitor dropped as "over twice $27.56" and was priced off a cost formula instead.
        BigDecimal plausibleRef = p0 != null ? p0 : positive(in.peerQ2()) != null ? positive(in.peerQ2()) : hardFloor;
        String plausibleWhat = p0 != null ? "your price today" : positive(in.peerQ2()) != null
                ? "your other branches' median" : "your minimum-margin price";
        if (cm != null && cfg.on(PricingModel.COMPETITORS_PLAUSIBILITY) && plausibleRef != null
                && (cm.compareTo(times(plausibleRef, 0.5)) < 0 || cm.compareTo(times(plausibleRef, 2)) > 0)) {
            r.flags.add(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE);
            competitorNote = "Competitor median " + money2(cm) + " is outside half to twice " + plausibleWhat + " ("
                    + money2(plausibleRef) + "), more likely a different product or pack than a market; ignored.";
            cm = null;
            cc = 0;
        } else if (cm != null) {
            competitorNote = "Median " + money2(cm) + " over " + cc + " competitor" + (cc == 1 ? "" : "s") + ".";
        } else {
            competitorNote = competitorsOn ? "No competitor prices on file for this item." : "Competitor prices are off.";
        }

        // ---- 5. anchor: the internal baseline, else the ladder ----------------------------
        BigDecimal peerQ2 = positive(in.peerQ2());
        boolean internalCredible = peerQ2 != null && in.peerStores() > 0
                && (cost == null || peerQ2.compareTo(cost) >= 0)
                && (p0 == null || (peerQ2.compareTo(times(p0, 0.3)) >= 0 && peerQ2.compareTo(times(p0, 3)) <= 0));
        String anchorSource;
        BigDecimal anchorValue;
        double anchorWeight;
        String externalRole = "absent";
        String anchorNote;
        boolean ladderCompetitorAnchor = false;
        if (cfg.on(PricingModel.ANCHOR_INTERNAL) && internalCredible) {
            anchorSource = "internal-peer";
            anchorValue = peerQ2;
            anchorWeight = 1;
            r.win = peerQ2;
            StringBuilder note = new StringBuilder("Median of what your other " + in.peerStores() + " branch"
                    + (in.peerStores() == 1 ? "" : "es") + " charge, " + money2(peerQ2) + ".");
            if (cm != null) {
                double ref = peerQ2.doubleValue();
                double divergence = Math.abs(cm.doubleValue() - ref) / ref;
                double screen = cfg.number(PricingModel.ANCHOR_EXTERNAL_DIVERGENCE) / 100;
                if (divergence > screen) {
                    externalRole = "ignored_divergent";
                    note.append(" Competitor median ").append(money2(cm)).append(" is ")
                            .append(fmt(divergence * 100, 1)).append("% away - beyond the ")
                            .append(fmt(screen * 100, 0)).append("% screen, so it is ignored.");
                } else {
                    double cap = cfg.number(PricingModel.ANCHOR_EXTERNAL_ADJUST_CAP) / 100;
                    double adjust = Stats.clamp(0.5 * (cm.doubleValue() / ref - 1), -cap, cap);
                    r.win = times(peerQ2, 1 + adjust);
                    externalRole = "directional_adjust";
                    note.append(" Competitor median ").append(money2(cm)).append(" is ")
                            .append(fmt((cm.doubleValue() / ref - 1) * 100, 1))
                            .append("% away: half of that, capped at ±").append(fmt(cap * 100, 0))
                            .append("%, moves the baseline ").append(signed(adjust * 100, 2)).append("% to ")
                            .append(money2(r.win)).append('.');
                }
            }
            anchorNote = note.toString();
        } else {
            Anchor a = null;
            if (cm != null) {
                a = new Anchor(cm, Anchor.COMPETITOR, cc);
                ladderCompetitorAnchor = true;
            } else if (peerQ2 != null && in.peerStores() > 0) {
                a = new Anchor(peerQ2, Anchor.PEER, in.peerStores());
            } else if (cost != null && in.benchmarkTargetMarginPct() != null) {
                BigDecimal b = priceAtMargin(cost, in.benchmarkTargetMarginPct());
                if (b != null) {
                    a = new Anchor(b, Anchor.BENCHMARK, 0);
                }
            } else if (in.ladderAnchor() != null && positive(in.ladderAnchor().value()) != null) {
                a = in.ladderAnchor();
            }
            if (a == null && own == null) {
                return Optional.empty();
            }
            if (a == null) {
                anchorSource = null;
                anchorValue = null;
                anchorWeight = 0;
                r.win = own;
                anchorNote = "No market anchor; this branch's own last price " + money2(own) + " is the start.";
            } else {
                anchorSource = a.source();
                anchorValue = a.value();
                anchorWeight = anchorWeight(a.source());
                if (own != null && cfg.on(PricingModel.BLEND_OWN_PRICE) && anchorWeight > 0 && anchorWeight < 1) {
                    r.win = a.value().multiply(BigDecimal.valueOf(anchorWeight))
                            .add(own.multiply(BigDecimal.valueOf(1 - anchorWeight))).setScale(MONEY_SCALE, ROUNDING);
                    anchorNote = capitalize(a.source()) + " anchor " + money2(a.value()) + " weighted "
                            + Math.round(anchorWeight * 100) + "/" + Math.round((1 - anchorWeight) * 100)
                            + " with your last price " + money2(own) + " = " + money2(r.win) + ".";
                } else {
                    r.win = a.value();
                    anchorNote = capitalize(a.source()) + " anchor " + money2(a.value())
                            + (a.observations() > 0 ? " (" + a.observations() + " observation"
                                    + (a.observations() == 1 ? "" : "s") + ")" : "") + ".";
                }
                if (ladderCompetitorAnchor) {
                    externalRole = "anchor";
                }
            }
            if (cfg.on(PricingModel.ANCHOR_INTERNAL) && !internalCredible) {
                anchorNote += peerQ2 == null || in.peerStores() == 0
                        ? " No other branch sells this item, so the ladder was used."
                        : " The peer median " + money2(peerQ2) + " is not credible against cost or today's price, "
                                + "so the ladder was used.";
            }
        }
        BigDecimal base = r.win;
        r.profit = r.win;
        r.step(PricingModel.ELASTICITY, "Price sensitivity", measured ? Step.APPLIED
                : cfg.on(PricingModel.ELASTICITY) ? Step.SKIPPED : Step.OFF, "e = " + fmt(e, 2) + ". " + eNote);
        r.step(PricingModel.SEGMENT, "Regular or occasional", segmentOn ? Step.APPLIED : Step.OFF,
                segmentOn ? in.ordersAtStore() + " order" + (in.ordersAtStore() == 1 ? "" : "s") + " at this branch in "
                        + "twelve months → " + segment + "; headline is the " + headline + " price."
                        : "Off; the optimal price leads.");
        r.step(PricingModel.COMPETITORS, "Competitor prices", cm != null ? Step.APPLIED
                : competitorsOn ? Step.SKIPPED : Step.OFF, competitorNote);
        r.step(PricingModel.ANCHOR_INTERNAL, "Anchor", Step.APPLIED, anchorNote);

        // ---- 6. corridor -----------------------------------------------------------------
        BigDecimal targetFloor = hardFloor;
        String corridorNote;
        boolean corridorOn = cfg.on(PricingModel.CORRIDOR_HISTORY);
        BigDecimal bandQ1 = positive(in.bandQ1());
        BigDecimal bandQ3 = positive(in.bandQ3());
        if (corridorOn && hardFloor != null && bandQ1 != null) {
            BigDecimal markupFloor = max(hardFloor, bandQ1);
            double minObs = cfg.number(PricingModel.CORRIDOR_MIN_OBSERVATIONS);
            if (in.bandN() < minObs) {
                double share = in.bandN() / minObs;
                markupFloor = hardFloor.add(markupFloor.subtract(hardFloor).multiply(BigDecimal.valueOf(share)))
                        .setScale(MONEY_SCALE, ROUNDING);
            }
            targetFloor = markupFloor;
            corridorNote = "Target floor " + money2(targetFloor) + ": the lower quartile of the prices this item "
                    + "carried (" + in.bandN() + " line" + (in.bandN() == 1 ? "" : "s")
                    + (in.bandN() < minObs ? ", shrunk toward the hard floor " + money2(hardFloor) : "") + ").";
        } else {
            corridorNote = corridorOn ? "No price history to build a corridor from; the hard floor "
                    + (hardFloor == null ? "is unknown" : money2(hardFloor)) + " is the target floor."
                    : "Off; the hard floor is the target floor.";
        }
        BigDecimal ceiling = null;
        String ceilingSource = null;
        BigDecimal peerQ3 = positive(in.peerQ3());
        if (peerQ3 != null) {
            ceiling = peerQ3;
            ceilingSource = "peer upper quartile";
        }
        if (cm != null && (ceiling == null || cm.compareTo(ceiling) > 0)) {
            ceiling = cm;
            ceilingSource = "competitor median";
        }
        BigDecimal marketRef = anchorValue != null ? anchorValue : r.win;
        BigDecimal fromMarket = times(marketRef, 1 + g.maxMarketDeviationPct().doubleValue() / 100);
        if (ceiling == null || fromMarket.compareTo(ceiling) > 0) {
            ceiling = fromMarket;
            ceilingSource = "market deviation";
        }
        if (corridorOn && bandQ3 != null && bandQ3.compareTo(ceiling) > 0) {
            ceiling = bandQ3;
            ceilingSource = "own upper quartile";
        }
        // With no market evidence at all - the start is only the cost-based benchmark, or nothing - a
        // formula is not a market, and it must not cap the price under what the item sells for today:
        // today's price stays within reach, so the move cap, not the formula, decides how far it falls.
        boolean noMarketEvidence = anchorSource == null || Anchor.BENCHMARK.equals(anchorSource);
        if (noMarketEvidence && p0 != null && p0.compareTo(ceiling) > 0) {
            ceiling = p0;
            ceilingSource = "today's price (no market evidence)";
        }
        if (cost != null && cfg.on(PricingModel.CEILING_PLAUSIBILITY)) {
            BigDecimal cap = times(cost, cfg.number(PricingModel.CEILING_COST_MULTIPLE));
            if (ceiling.compareTo(cap) > 0) {
                ceiling = cap;
                ceilingSource = "cost multiple";
            }
        }
        if (targetFloor != null && ceiling.compareTo(targetFloor) < 0) {
            ceiling = targetFloor;
            ceilingSource = "floor";
            r.flags.add(Recommendation.FLAG_CEILING_AT_FLOOR);
        }
        r.step(PricingModel.CORRIDOR_HISTORY, "Corridor", corridorOn && bandQ1 != null && hardFloor != null
                ? Step.APPLIED : corridorOn ? Step.SKIPPED : Step.OFF,
                corridorNote + " Ceiling " + money2(ceiling) + " (" + ceilingSource + ").");

        // ---- 7. profit-max target ------------------------------------------------------
        BigDecimal curveP0 = p0 != null ? p0 : r.win;
        BigDecimal profitPeak = null;
        boolean profitMaxOn = cfg.on(PricingModel.AGGRESSIVE_PROFIT_MAX);
        String profitStatus;
        String profitNote;
        double absE = Math.abs(e);
        if (profitMaxOn && cost != null && absE > 1 && targetFloor != null && ceiling.compareTo(targetFloor) > 0) {
            profitPeak = times(cost, absE / (absE - 1));
            double gamma = cfg.number(PricingModel.AGGRESSIVE_RISK_AVERSION);
            BigDecimal lo = max(targetFloor, times(cost, 1.0001));
            r.profit = riskAdjustedProfitMax(cost, curveP0, lo, ceiling, e, sd, gamma);
            profitStatus = Step.APPLIED;
            profitNote = "Profit per order peaks at " + money2(profitPeak) + " for e = " + fmt(e, 2)
                    + "; the risk-adjusted pick (γ = " + fmt(gamma, 1) + ", sd " + fmt(sd, 2) + ") is "
                    + money2(r.profit) + ".";
        } else if (profitMaxOn && cost != null && absE <= 1) {
            r.profit = ceiling;
            profitStatus = Step.SKIPPED;
            profitNote = "|e| ≤ 1: profit keeps rising with price, so the ceiling " + money2(ceiling) + " is the target.";
        } else {
            r.profit = aggressive(r.win, ceiling, e);
            profitStatus = profitMaxOn ? Step.SKIPPED : Step.OFF;
            profitNote = (profitMaxOn ? "No cost on file; " : "Off; ") + "a step above the optimal price ("
                    + fmt(aggressiveStep(e) * 100, 0) + "%) capped at the ceiling.";
        }
        r.step(PricingModel.AGGRESSIVE_PROFIT_MAX, "Profit-max target", profitStatus, profitNote);

        // ---- 8. clip the win target into the corridor ------------------------------------
        r.win = clamp(r.win, targetFloor, max(r.profit, targetFloor));

        // ---- 9. demand -------------------------------------------------------------------
        BigDecimal afterDemand = null;
        Demand demand = in.demand();
        if (!cfg.on(PricingModel.DEMAND)) {
            r.step(PricingModel.DEMAND, "Demand", Step.OFF, "Off.");
        } else if (demand == null) {
            r.step(PricingModel.DEMAND, "Demand", Step.SKIPPED, "No sales in the last 180 days to read a pace from.");
        } else {
            long age = in.lastSale() == null || in.today() == null ? 0
                    : Math.max(0, ChronoUnit.DAYS.between(in.lastSale(), in.today()));
            double maxAge = cfg.number(PricingModel.DEMAND_MAX_AGE_DAYS);
            if (age > maxAge) {
                r.step(PricingModel.DEMAND, "Demand", Step.SKIPPED, "Last sale " + age + " days ago, older than "
                        + fmt(maxAge, 0) + " days: the signal is stale, so the price holds.");
            } else {
                double maxMove = cfg.number(PricingModel.DEMAND_MAX_MOVE);
                double move = Stats.clamp(demand.movePercent() * (maxMove / 3.0), -maxMove, maxMove);
                r.scaleBoth(1 + move / 100);
                afterDemand = r.win;
                r.step(PricingModel.DEMAND, "Demand", Step.APPLIED, demand.label() + ": " + signed(move, 2)
                        + "% (recent pace " + demand.recentVelocity() + "/wk against " + demand.expectedVelocity()
                        + "/wk expected, confidence " + demand.confidence().toLowerCase(Locale.ROOT) + ").");
            }
        }

        // ---- 10. commodity ---------------------------------------------------------------
        BigDecimal afterCommodity = null;
        if (!cfg.on(PricingModel.COMMODITY)) {
            r.step(PricingModel.COMMODITY, "Commodity", Step.OFF, "Off.");
        } else if (in.commodityPct90() == null) {
            r.step(PricingModel.COMMODITY, "Commodity", Step.SKIPPED, "No commodity linked to this item.");
        } else {
            double share = cfg.number(PricingModel.COMMODITY_PASS_THROUGH) / 100;
            double move = in.commodityPct90().doubleValue() * share;
            r.scaleBoth(1 + move / 100);
            afterCommodity = r.win;
            r.step(PricingModel.COMMODITY, "Commodity", Step.APPLIED, "Index moved "
                    + signed(in.commodityPct90().doubleValue(), 1) + "% over ninety days; " + fmt(share * 100, 0)
                    + "% of that (" + signed(move, 2) + "%) reaches the price.");
        }

        // ---- 11. local market ------------------------------------------------------------
        BigDecimal afterRegion = null;
        if (!cfg.on(PricingModel.LOCAL_MARKET)) {
            r.step(PricingModel.LOCAL_MARKET, "Local market", Step.OFF, "Off.");
        } else if (in.rpp() == null) {
            r.step(PricingModel.LOCAL_MARKET, "Local market", Step.SKIPPED, "This branch is priced nationally.");
        } else if (ladderCompetitorAnchor) {
            r.step(PricingModel.LOCAL_MARKET, "Local market", Step.SKIPPED,
                    "The anchor is a competitor price, already a local market price.");
        } else {
            double weight = cfg.number(PricingModel.LOCAL_MARKET_WEIGHT) / 100;
            double mult = 1 - ((in.rpp().doubleValue() - 100) / 100) * weight;
            r.win = times(r.win, mult);
            afterRegion = r.win;
            r.step(PricingModel.LOCAL_MARKET, "Local market", Step.APPLIED, "Regional price parity "
                    + in.rpp().stripTrailingZeros().toPlainString() + " → × " + fmt(mult, 3) + " on the win price.");
        }

        // ---- 12. learning ----------------------------------------------------------------
        DecisionPatterns.Learning learning = track.learning();
        if (!cfg.on(PricingModel.LEARNING)) {
            r.step(PricingModel.LEARNING, "Your decisions", Step.OFF, "Off.");
        } else if (learning == null || !learning.available()) {
            r.step(PricingModel.LEARNING, "Your decisions", Step.SKIPPED,
                    learning == null ? "No decisions recorded yet." : learning.note());
        } else {
            r.scaleBoth(1 + learning.movePct() / 100);
            r.step(PricingModel.LEARNING, "Your decisions", Step.APPLIED, learning.note());
        }
        DecisionPatterns.Habit habit = track.habit();
        if (cfg.on(PricingModel.LEARNING_STRATEGY) && habit != null) {
            headline = habit.headlineTier();
            r.step(PricingModel.LEARNING_STRATEGY, "Your usual strategy", Step.APPLIED, "You picked "
                    + habit.strategyKey() + " in " + habit.picks() + " of " + habit.total() + " bulk decisions ("
                    + fmt(habit.sharePct(), 0) + "%); the " + headline + " price leads.");
        }

        // ---- 13. tier gap at the target stage --------------------------------------------
        double contested = 0.5 * Math.min(cc / 5.0, 1) + 0.5 * sensitivity;
        double gap = cfg.number(PricingModel.TIER_GAP_BASE) / 100
                + cfg.number(PricingModel.TIER_GAP_CONTESTED) / 100 * contested;
        boolean gapOn = cfg.on(PricingModel.TIER_GAP);
        if (gapOn) {
            BigDecimal needed = times(r.win, 1 + gap);
            if (r.profit.compareTo(needed) < 0) {
                r.profit = min(needed, ceiling);
                if (r.profit.compareTo(needed) < 0 && targetFloor != null) {
                    r.win = max(targetFloor, r.profit.divide(BigDecimal.valueOf(1 + gap), MONEY_SCALE, ROUNDING));
                }
            }
            r.step(PricingModel.TIER_GAP, "Tier gap", Step.APPLIED, "Aggressive at least " + fmt(gap * 100, 1)
                    + "% above optimal (contestedness " + fmt(contested, 2) + ").");
        } else {
            if (r.profit.compareTo(r.win) < 0) {
                r.profit = r.win;
            }
            r.step(PricingModel.TIER_GAP, "Tier gap", Step.OFF, "Off; the tiers may sit together.");
        }
        BigDecimal winTarget = r.win;
        BigDecimal profitTarget = r.profit;

        // ---- 14. phase in ----------------------------------------------------------------
        double maturity = 1;
        if (!cfg.on(PricingModel.TRUST_RAMP)) {
            r.step(PricingModel.TRUST_RAMP, "Phase-in", Step.OFF, "Off; the targets are quoted at once.");
        } else if (p0 == null) {
            r.step(PricingModel.TRUST_RAMP, "Phase-in", Step.SKIPPED,
                    "No price today to phase in from; the targets stand.");
        } else {
            double m = DecisionPatterns.maturity(track.priorApplied(), cfg.number(PricingModel.TRUST_RAMP_HALF_POINT),
                    cfg.number(PricingModel.TRUST_RAMP_LAUNCH) / 100);
            double wobble = DecisionPatterns.wobble(in.rampSalt(), cfg.number(PricingModel.TRUST_RAMP_WOBBLE) / 100, m);
            maturity = Stats.clamp(m + wobble, 0, 1);
            r.win = p0.add(winTarget.subtract(p0).multiply(BigDecimal.valueOf(maturity))).setScale(MONEY_SCALE, ROUNDING);
            r.profit = p0.add(profitTarget.subtract(p0).multiply(BigDecimal.valueOf(maturity))).setScale(MONEY_SCALE, ROUNDING);
            r.step(PricingModel.TRUST_RAMP, "Phase-in", Step.APPLIED, track.priorApplied() + " prior decision"
                    + (track.priorApplied() == 1 ? "" : "s") + " for this item here → maturity " + fmt(m, 2)
                    + (wobble != 0 ? " (wobble " + signed(wobble, 3) + ")" : "") + ": " + fmt(maturity * 100, 0)
                    + "% of the way from today's " + money2(p0) + " to the targets " + money2(winTarget) + " / "
                    + money2(profitTarget) + ".");
        }

        // ---- 15. the final band: hard floor, ceiling, move cap ---------------------------
        BigDecimal lo = hardFloor;
        BigDecimal hi = ceiling;
        boolean moveCapOn = cfg.on(PricingModel.MOVE_CAP);
        String bandNote;
        if (moveCapOn && p0 != null) {
            double cap = cfg.number(PricingModel.MOVE_CAP_MAX_PCT) / 100;
            BigDecimal capLo = times(p0, 1 - cap);
            BigDecimal capHi = times(p0, 1 + cap);
            BigDecimal lo2 = max(lo, capLo);
            BigDecimal hi2 = min(hi, capHi);
            if (lo2.compareTo(hi2) > 0) {
                r.flags.add(Recommendation.FLAG_CONSTRAINT_CONFLICT);
                bandNote = "The ±" + fmt(cap * 100, 0) + "% move cap cannot be met inside floor " + money2(lo)
                        + " and ceiling " + money2(hi) + "; the floor and ceiling win.";
            } else {
                lo = lo2;
                hi = hi2;
                bandNote = "Floor " + money2(lo) + " · ceiling " + money2(hi) + ", within ±" + fmt(cap * 100, 0)
                        + "% of today's " + money2(p0) + ".";
            }
        } else {
            bandNote = "Floor " + (lo == null ? "none" : money2(lo)) + " · ceiling " + money2(hi)
                    + (moveCapOn ? " (no price today, so no move cap)." : "; the move cap is off.");
        }
        r.win = clamp(r.win, lo, hi);
        r.profit = clamp(r.profit, lo, hi);
        r.step(PricingModel.MOVE_CAP, "Guardrails", moveCapOn && p0 != null ? Step.APPLIED
                : moveCapOn ? Step.SKIPPED : Step.OFF, bandNote);

        // ---- 16. tier gap fitted inside the band ------------------------------------------
        // The doc's rungs (§3 step 10): leave alone → raise the aggressive tier as far as the
        // ceiling → trim the win tier as far as the floor → part the two across the whole
        // band when it still holds 3% → converge. Bounds are never widened to fit the gap.
        if (gapOn) {
            BigDecimal winBefore = r.win;
            BigDecimal profitBefore = r.profit;
            BigDecimal needed = times(r.win, 1 + gap);
            String fitNote = null;
            if (r.profit.compareTo(needed) < 0) {
                r.profit = min(needed, hi);
                fitNote = "Aggressive raised to " + money2(r.profit) + " to keep the " + fmt(gap * 100, 1) + "% gap.";
                if (r.profit.compareTo(needed) < 0) {
                    BigDecimal trimmed = r.profit.divide(BigDecimal.valueOf(1 + gap), MONEY_SCALE, ROUNDING);
                    if (lo == null || trimmed.compareTo(lo) >= 0) {
                        r.win = trimmed;
                        fitNote = "The ceiling caps the aggressive tier at " + money2(r.profit)
                                + ", so the optimal tier is trimmed to " + money2(r.win) + " to keep the "
                                + fmt(gap * 100, 1) + "% gap.";
                    } else if (lo != null && hi.doubleValue() / lo.doubleValue() - 1 >= PARTIAL_GAP_FLOOR) {
                        r.win = lo;
                        r.profit = hi;
                        r.flags.add(Recommendation.FLAG_TIER_GAP_INFEASIBLE);
                        fitNote = "The band cannot hold the full " + fmt(gap * 100, 1)
                                + "% gap; the tiers are parted across it: optimal at the floor, aggressive at the ceiling.";
                    } else {
                        r.profit = r.win;
                        r.flags.add(Recommendation.FLAG_TIER_GAP_INFEASIBLE);
                        fitNote = "The band is too narrow for a meaningful gap; both tiers converge at the win price.";
                    }
                }
            }
            if (fitNote != null && (r.win.compareTo(winBefore) != 0 || r.profit.compareTo(profitBefore) != 0)) {
                r.step(TIER_GAP_FIT, "Tier gap inside the band", Step.APPLIED, fitNote);
            }
        } else if (r.profit.compareTo(r.win) < 0) {
            r.profit = r.win;
        }

        // ---- 17. rounding ----------------------------------------------------------------
        if (cfg.on(PricingModel.ROUNDING)) {
            r.win = roundInside(r.win, lo, hi);
            r.profit = roundInside(r.profit, lo, hi);
            if (r.profit.compareTo(r.win) < 0) {
                r.profit = r.win;
            }
            r.step(PricingModel.ROUNDING, "Rounded", Step.APPLIED, "To retail price points, inside the band.");
        } else {
            r.win = round2(r.win);
            r.profit = round2(r.profit);
            r.step(PricingModel.ROUNDING, "Rounded", Step.OFF, "To the cent only.");
        }

        return Optional.of(new Recommendation(anchorValue, anchorSource, anchorWeight, own, base, afterDemand,
                afterCommodity, afterRegion, hardFloor, round2(ceiling), r.win, r.profit,
                round2(targetFloor), ceilingSource, BigDecimal.valueOf(e).setScale(4, ROUNDING), eConfidence,
                segment, headline, round2(winTarget), round2(profitTarget), round2(profitPeak), maturity,
                contested, externalRole, List.copyOf(r.steps), List.copyOf(r.flags)));
    }

    /**
     * The price on a grid across the corridor that maximises {@code E[profit] − γ·SD[profit]},
     * the expectation over {@code e ~ Normal(ê, sd)} (§3 step 8a). Units are scale-free:
     * {@code (p ÷ p0)^e}, so only the shape of the curve matters.
     */
    static BigDecimal riskAdjustedProfitMax(BigDecimal cost, BigDecimal p0, BigDecimal lo, BigDecimal hi, double e,
            double sd, double gamma) {
        double c = cost.doubleValue();
        double base = p0.doubleValue();
        double from = lo.doubleValue();
        double to = hi.doubleValue();
        if (to <= from) {
            return lo;
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        double best = from;
        for (int i = 0; i < PROFIT_GRID_POINTS; i++) {
            double p = from + (to - from) * i / (PROFIT_GRID_POINTS - 1);
            double mean = 0;
            double[] profits = new double[QUAD_OFFSETS.length];
            for (int k = 0; k < QUAD_OFFSETS.length; k++) {
                double ek = Stats.clamp(e + QUAD_OFFSETS[k] * sd, -3, -ELASTICITY_MIN_ABS);
                profits[k] = (p - c) * Math.pow(p / base, ek);
                mean += QUAD_WEIGHTS[k] * profits[k];
            }
            double var = 0;
            for (int k = 0; k < QUAD_OFFSETS.length; k++) {
                var += QUAD_WEIGHTS[k] * (profits[k] - mean) * (profits[k] - mean);
            }
            double score = mean - gamma * Math.sqrt(Math.max(0, var));
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return BigDecimal.valueOf(best).setScale(MONEY_SCALE, ROUNDING);
    }

    /** With a cost, the minimum-margin price; without one, never below the own reference or the lower quartile. */
    public static BigDecimal floor(BigDecimal cost, BigDecimal minMarginPct, BigDecimal ownRef, BigDecimal bandQ1) {
        if (cost != null && cost.signum() > 0) {
            return priceAtMargin(cost, minMarginPct);
        }
        return min(ownRef, bandQ1);
    }

    /** The larger of the peers' upper quartile and the anchor (or own reference) plus the market deviation. */
    public static BigDecimal ceiling(BigDecimal peerQ3, BigDecimal anchorOrRef, BigDecimal maxDeviationPct) {
        BigDecimal fromAnchor = anchorOrRef == null ? null
                : times(anchorOrRef, 1 + maxDeviationPct.doubleValue() / 100);
        return max(peerQ3 == null ? BigDecimal.ZERO : peerQ3, fromAnchor);
    }

    /** The step the heuristic aggressive tier takes above optimal: shrinks as the item gets more elastic. */
    static double aggressiveStep(double beta) {
        return Stats.clamp(0.10 * 1.2 / Math.max(0.6, Math.abs(beta)), 0.04, 0.15);
    }

    /** The stretch price: a step above optimal that shrinks as the item gets more elastic, never over the ceiling. */
    public static BigDecimal aggressive(BigDecimal optimal, BigDecimal ceiling, double beta) {
        BigDecimal stretched = times(optimal, 1 + aggressiveStep(beta));
        return roundPricePoint(min(ceiling, stretched));
    }

    /** Rounds to the price step, then back inside {@code [lo, hi]} if the rounding stepped out. */
    static BigDecimal roundInside(BigDecimal price, BigDecimal lo, BigDecimal hi) {
        BigDecimal rounded = roundPricePoint(price);
        if (hi != null && rounded.compareTo(hi) > 0) {
            rounded = roundPricePoint(price, RoundingMode.FLOOR);
        }
        if (lo != null && rounded.compareTo(lo) < 0) {
            rounded = roundPricePoint(price, RoundingMode.CEILING);
        }
        if ((hi != null && rounded.compareTo(hi) > 0) || (lo != null && rounded.compareTo(lo) < 0)) {
            rounded = round2(clamp(price, lo, hi));
        }
        return rounded;
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static String money2(BigDecimal v) {
        return v == null ? "—" : "$" + v.setScale(2, ROUNDING).toPlainString();
    }

    private static String fmt(double v, int places) {
        return BigDecimal.valueOf(v).setScale(places, ROUNDING).toPlainString();
    }

    private static String signed(double v, int places) {
        String s = fmt(v, places);
        return v > 0 ? "+" + s : s;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- 4.7 rounding --------------------------------------------------------------------

    /** The retail rounding step for a price: 0.01 under 10, 0.05 under 100, 0.50 under 1000, else 1.00. */
    public static BigDecimal priceStep(BigDecimal price) {
        BigDecimal abs = price.abs();
        if (abs.compareTo(BigDecimal.TEN) < 0) {
            return new BigDecimal("0.01");
        }
        if (abs.compareTo(HUNDRED) < 0) {
            return new BigDecimal("0.05");
        }
        if (abs.compareTo(BigDecimal.valueOf(1000)) < 0) {
            return new BigDecimal("0.50");
        }
        return BigDecimal.ONE;
    }

    public static BigDecimal roundPricePoint(BigDecimal price) {
        return roundPricePoint(price, ROUNDING);
    }

    /** Rounds to the price step in the given direction, so a caller can round back inside a band. */
    public static BigDecimal roundPricePoint(BigDecimal price, RoundingMode mode) {
        if (price == null) {
            return null;
        }
        BigDecimal step = priceStep(price);
        BigDecimal steps = price.divide(step, 0, mode);
        return steps.multiply(step).setScale(2, ROUNDING);
    }

    // ---- 3.3 score -----------------------------------------------------------------------

    /**
     * The inputs of the 0-100 score; every one may be null, in which case its part scores 0.
     *
     * @param demandLevel {@code high}/{@code medium}/{@code low}
     * @param followRatePct decision follow-rate; null without decisions
     * @param decisions how many decisions the follow-rate rests on
     */
    public record ScoreInputs(String demandLevel, BigDecimal anchor, BigDecimal currentPrice,
            BigDecimal commodityPct90, BigDecimal marginPct, BigDecimal weeksOfCover, BigDecimal followRatePct,
            int decisions) {
    }

    /** The score, its tier and each part's contribution. */
    public record Score(int score, String tier, int demand, int priceGap, int commodity, int margin, int cover,
            int followRate, BigDecimal priceGapPct) {

        public static final String STRONG = "strong";
        public static final String WATCH = "watch";
        public static final String RISK = "risk";
    }

    public static Score score(ScoreInputs in) {
        int demand = in.demandLevel() == null ? 0 : switch (in.demandLevel()) {
            case Demand.HIGH -> 16;
            case Demand.MEDIUM -> 5;
            case Demand.LOW -> -14;
            default -> 0;
        };
        BigDecimal gapPct = in.anchor() == null || in.currentPrice() == null ? null
                : pct(in.anchor().subtract(in.currentPrice()), in.currentPrice());
        int priceGap = 0;
        if (gapPct != null) {
            double g = gapPct.doubleValue();
            priceGap = g >= 8 ? 16 : g >= 3 ? 5 : g <= -8 ? -16 : g <= -3 ? -8 : 0;
        }
        int commodity = 0;
        if (in.commodityPct90() != null) {
            double k = in.commodityPct90().doubleValue();
            commodity = k >= 2 ? 6 : k <= -1.5 ? -5 : 0;
        }
        int margin = 0;
        if (in.marginPct() != null) {
            double m = in.marginPct().doubleValue();
            margin = m >= 30 ? 5 : m < 22 ? -9 : 0;
        }
        int cover = 0;
        if (in.weeksOfCover() != null) {
            double c = in.weeksOfCover().doubleValue();
            cover = c > 16 ? -10 : c < 3 ? -5 : c >= 4 && c <= 12 ? 7 : 0;
        }
        int follow = 0;
        if (in.followRatePct() != null) {
            double f = in.followRatePct().doubleValue();
            follow = f >= 70 ? 6 : f < 40 && in.decisions() >= 3 ? -7 : 0;
        }
        int total = (int) Stats.clamp(42 + demand + priceGap + commodity + margin + cover + follow, 5, 97);
        String tier = total >= 75 ? Score.STRONG : total >= 45 ? Score.WATCH : Score.RISK;
        return new Score(total, tier, demand, priceGap, commodity, margin, cover, follow, gapPct);
    }

    // ---- the rest --------------------------------------------------------------------------

    /** The 90-day price drift the forecast reports: {@code pct90 × 0.72 + movePercent × 1.6}. */
    public static BigDecimal driftPct90(BigDecimal commodityPct90, double movePercent) {
        double k = commodityPct90 == null ? 0 : commodityPct90.doubleValue();
        return BigDecimal.valueOf(k * 0.72 + movePercent * 1.6).setScale(2, ROUNDING);
    }

    /** {@code onHand / (units90 × 7 / 90)}; null when nothing sold in the 90 days. */
    public static BigDecimal weeksOfCover(BigDecimal onHand, BigDecimal units90) {
        if (onHand == null || units90 == null || units90.signum() == 0) {
            return null;
        }
        BigDecimal perWeek = units90.multiply(BigDecimal.valueOf(7)).divide(BigDecimal.valueOf(90), RATIO_SCALE, ROUNDING);
        BigDecimal weeks = div(onHand, perWeek);
        return weeks == null ? null : weeks.setScale(1, ROUNDING);
    }

    /** Decision follow-rate, 0-100; null with no decisions. */
    public static BigDecimal followRate(long followed, long total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(followed).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 1, ROUNDING);
    }
}

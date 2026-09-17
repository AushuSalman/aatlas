package com.aatlas.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * The chain, one value per step, so a derivation panel can show every multiplier exactly
     * once. A null step value means the step was skipped for want of its input.
     *
     * @param base the value after the market/history blend
     * @param afterDemand null when demand was unknown
     * @param afterCommodity null when the item has no commodity
     * @param afterRegion null when no regional index applied (competitor anchor or none)
     * @param floor null when neither a cost nor an own reference exists
     */
    public record Recommendation(BigDecimal anchor, String anchorSource, double anchorWeight, BigDecimal ownRef,
            BigDecimal base, BigDecimal afterDemand, BigDecimal afterCommodity, BigDecimal afterRegion,
            BigDecimal floor, BigDecimal ceiling, BigDecimal optimal, BigDecimal aggressive) {
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

    /**
     * Spec 3.1. Empty when neither an anchor nor an own reference exists: not priceable.
     *
     * @param cost nullable
     * @param anchor nullable market anchor (benchmark anchors are bare: cost over margin)
     * @param ownRef the pair's own last price, nullable
     * @param demand nullable; skipped when null
     * @param commodityPct90 the 90-day index move for the item's commodity, nullable
     * @param msaMult regional multiplier, 1 when none; applied unless the anchor is a competitor
     * @param beta own-price elasticity (negative)
     * @param bandQ1 the pair's lower price quartile, for the no-cost floor
     * @param peerQ3 the other branches' upper quartile, for the ceiling
     */
    public static Optional<Recommendation> recommend(BigDecimal cost, Anchor anchor, BigDecimal ownRef, Demand demand,
            BigDecimal commodityPct90, double msaMult, double beta, Reference.Guardrails guardrails,
            BigDecimal bandQ1, BigDecimal peerQ3) {
        BigDecimal a = anchor == null || anchor.value() == null || anchor.value().signum() <= 0 ? null : anchor.value();
        BigDecimal r = ownRef == null || ownRef.signum() <= 0 ? null : ownRef;
        if (a == null && r == null) {
            return Optional.empty();
        }
        String source = a == null ? null : anchor.source();
        double w = anchorWeight(source);
        BigDecimal base;
        if (r == null) {
            base = a;
        } else if (a == null) {
            base = r;
        } else {
            base = a.multiply(BigDecimal.valueOf(w)).add(r.multiply(BigDecimal.valueOf(1 - w)))
                    .setScale(MONEY_SCALE, ROUNDING);
        }
        BigDecimal running = base;
        BigDecimal afterDemand = null;
        if (demand != null) {
            running = times(running, 1 + demand.movePercent() / 100);
            afterDemand = running;
        }
        BigDecimal afterCommodity = null;
        if (commodityPct90 != null) {
            running = times(running, 1 + commodityPct90.doubleValue() * COMMODITY_PASS_THROUGH / 100);
            afterCommodity = running;
        }
        BigDecimal afterRegion = null;
        if (!Anchor.COMPETITOR.equals(source) && msaMult != 1) {
            running = times(running, msaMult);
            afterRegion = running;
        }
        BigDecimal floor = floor(cost, guardrails.minMarginPct(), r, bandQ1);
        BigDecimal ceiling = ceiling(peerQ3, a != null ? a : r, guardrails.maxMarketDeviationPct());
        BigDecimal optimal = roundPricePoint(clamp(running, floor, ceiling));
        BigDecimal aggressive = aggressive(optimal, ceiling, beta);
        return Optional.of(new Recommendation(a, source, w, r, base, afterDemand, afterCommodity, afterRegion,
                floor, ceiling, optimal, aggressive));
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

    /** The stretch price: a step above optimal that shrinks as the item gets more elastic, never over the ceiling. */
    public static BigDecimal aggressive(BigDecimal optimal, BigDecimal ceiling, double beta) {
        double step = Stats.clamp(0.10 * 1.2 / Math.max(0.6, Math.abs(beta)), 0.04, 0.15);
        BigDecimal stretched = times(optimal, 1 + step);
        return roundPricePoint(min(ceiling, stretched));
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

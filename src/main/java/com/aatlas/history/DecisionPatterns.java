package com.aatlas.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a tenant's own decision history says, reduced to the three numbers the pricing
 * model reads: how far they habitually move from what was suggested (the <b>lean</b>), how
 * much of a track record an item has at a branch (the <b>maturity</b> of the trust ramp),
 * and which bulk strategy they reach for (the <b>habit</b>).
 *
 * <p>Pure: rows in, numbers out, no clock. Every figure is bounded by the model's own knobs
 * and re-derived from the raw rows on each call, so a lean cannot ratchet - the same
 * decisions produce the same lean however many times they are read.
 */
public final class DecisionPatterns {

    /** Deviations wider than this are a different product or unit, not a preference. */
    static final double OUTLIER_PCT = 50;

    /** The cap on the lean's confidence: even a unanimous, well-evidenced history is not certainty. */
    static final double MAX_CONFIDENCE = 0.8;

    private DecisionPatterns() {
    }

    /**
     * One past decision: what was suggested and what was applied or sold at.
     *
     * @param basis {@code item-store}, {@code item} or {@code tenant} - how close the row is to
     *              the pair being priced; the closest available basis is the one that is learned from
     */
    public record Acceptance(LocalDate date, BigDecimal suggested, BigDecimal actual, String basis) {

        public static final String ITEM_STORE = "item-store";
        public static final String ITEM = "item";
        public static final String TENANT = "tenant";
    }

    /**
     * The lean.
     *
     * @param available false when the gates did not pass; {@code note} says which
     * @param decisions rows that counted
     * @param effectiveN the decayed weight behind the lean
     * @param biasPct the weighted median of (applied − suggested) ÷ suggested, in percent
     * @param confidence 0-0.8
     * @param movePct what the model applies: the bias, capped, times the confidence
     */
    public record Learning(boolean available, int decisions, double effectiveN, double biasPct, double confidence,
            double movePct, String basis, String note) {

        public static Learning none(String note) {
            return new Learning(false, 0, 0, 0, 0, 0, null, note);
        }
    }

    /**
     * Which knobs the lean reads and how it speaks, per side: the sell side learns from prices
     * applied against a suggestion, the buy side from costs agreed against a target.
     */
    public record Keys(String windowDays, String halfLifeDays, String minDecisions, String deadband, String maxMove,
            String verb, String reference, String presentVerb, String steadyReference) {

        public static Keys of(PricingModel.Side side) {
            return side == PricingModel.Side.BUY
                    ? new Keys(PricingModel.BUY_LEARNING_WINDOW_DAYS, PricingModel.BUY_LEARNING_HALF_LIFE_DAYS,
                            PricingModel.BUY_LEARNING_MIN_DECISIONS, PricingModel.BUY_LEARNING_DEADBAND,
                            PricingModel.BUY_LEARNING_MAX_MOVE, "agreed", "the target", "agree", "the target")
                    : new Keys(PricingModel.LEARNING_WINDOW_DAYS, PricingModel.LEARNING_HALF_LIFE_DAYS,
                            PricingModel.LEARNING_MIN_DECISIONS, PricingModel.LEARNING_DEADBAND,
                            PricingModel.LEARNING_MAX_MOVE, "applied", "the suggestion", "apply", "what is suggested");
        }
    }

    /** The sell-side lean: see {@link #learn(List, LocalDate, PricingModel.Config, PricingModel.Side)}. */
    public static Learning learn(List<Acceptance> rows, LocalDate today, PricingModel.Config config) {
        return learn(rows, today, config, PricingModel.Side.SELL);
    }

    /**
     * The lean, from the closest basis that passes the gates: item-at-branch rows first,
     * then the item anywhere, then the whole tenant.
     */
    public static Learning learn(List<Acceptance> rows, LocalDate today, PricingModel.Config config,
            PricingModel.Side side) {
        Keys keys = Keys.of(side);
        if (rows == null || rows.isEmpty()) {
            return Learning.none("No decisions recorded yet.");
        }
        for (String basis : List.of(Acceptance.ITEM_STORE, Acceptance.ITEM, Acceptance.TENANT)) {
            List<Acceptance> subset = rows.stream().filter(r -> basis.equals(r.basis())).toList();
            if (subset.isEmpty()) {
                continue;
            }
            Learning l = learnFrom(subset, basis, today, config, keys);
            if (l.available()) {
                return l;
            }
        }
        // Nothing passed; report the widest basis so the note is about the most rows.
        return learnFrom(rows, Acceptance.TENANT, today, config, keys);
    }

    static Learning learnFrom(List<Acceptance> rows, String basis, LocalDate today, PricingModel.Config config) {
        return learnFrom(rows, basis, today, config, Keys.of(PricingModel.Side.SELL));
    }

    static Learning learnFrom(List<Acceptance> rows, String basis, LocalDate today, PricingModel.Config config,
            Keys keys) {
        int window = (int) config.number(keys.windowDays());
        double halfLife = config.number(keys.halfLifeDays());
        int minDecisions = (int) config.number(keys.minDecisions());
        double deadband = config.number(keys.deadband());
        double maxMove = config.number(keys.maxMove());

        List<double[]> weighted = new ArrayList<>();
        for (Acceptance r : rows) {
            if (r.suggested() == null || r.actual() == null || r.suggested().signum() <= 0 || r.actual().signum() <= 0) {
                continue;
            }
            long age = r.date() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(r.date(), today));
            if (age > window) {
                continue;
            }
            double dev = r.actual().subtract(r.suggested()).doubleValue() / r.suggested().doubleValue() * 100;
            if (Math.abs(dev) > OUTLIER_PCT) {
                continue;
            }
            double w = halfLife <= 0 ? 1 : Math.pow(0.5, age / halfLife);
            weighted.add(new double[] {dev, w});
        }
        int n = weighted.size();
        if (n < minDecisions) {
            return new Learning(false, n, 0, 0, 0, 0, basis,
                    n == 0 ? "No usable decisions in the window."
                            : n + " decision" + (n == 1 ? "" : "s") + " in the window; " + minDecisions + " needed.");
        }
        double sumW = weighted.stream().mapToDouble(x -> x[1]).sum();
        double bias = weightedMedian(weighted, sumW);
        double confidence = Math.min(MAX_CONFIDENCE, sumW / (sumW + 5));
        double biasRounded = Math.round(bias * 100) / 100.0;
        if (Math.abs(bias) < deadband) {
            return new Learning(false, n, round2(sumW), biasRounded, round2(confidence), 0, basis,
                    "You " + keys.presentVerb() + " about " + keys.steadyReference() + " (" + signed(biasRounded)
                            + "% on " + n + " decision" + (n == 1 ? "" : "s") + ").");
        }
        double move = Stats.clamp(bias, -maxMove, maxMove) * confidence;
        String note = "You " + keys.verb() + " " + signed(biasRounded) + "% vs " + keys.reference() + " across " + n
                + " decision" + (n == 1 ? "" : "s") + " (" + basisLabel(basis) + "); leaning " + signed(round2(move))
                + "%.";
        return new Learning(true, n, round2(sumW), biasRounded, round2(confidence), round2(move), basis, note);
    }

    private static double weightedMedian(List<double[]> weighted, double sumW) {
        List<double[]> sorted = new ArrayList<>(weighted);
        sorted.sort(Comparator.comparingDouble(x -> x[0]));
        double acc = 0;
        for (double[] x : sorted) {
            acc += x[1];
            if (acc >= sumW / 2) {
                return x[0];
            }
        }
        return sorted.get(sorted.size() - 1)[0];
    }

    static String basisLabel(String basis) {
        return switch (basis == null ? "" : basis) {
            case Acceptance.ITEM_STORE -> "this item at this branch";
            case Acceptance.ITEM -> "this item, every branch";
            default -> "across the business";
        };
    }

    // ---- habit -------------------------------------------------------------------------

    /** The bulk strategy picked most, when it is a clear habit rather than a coin toss. */
    public record Habit(String strategyKey, long picks, long total, double sharePct) {

        /** {@code aggressive} for a profit-first habit, {@code optimal} otherwise. */
        public String headlineTier() {
            return "max-profit".equals(strategyKey) ? "aggressive" : "optimal";
        }
    }

    /**
     * @param picks strategy key → how often it was applied
     * @param minPicks fewest picks of the leading strategy before it counts
     * @param minSharePct the leading strategy's share of all picks, in percent
     */
    public static Optional<Habit> habit(Map<String, Long> picks, int minPicks, double minSharePct) {
        if (picks == null || picks.isEmpty()) {
            return Optional.empty();
        }
        long total = picks.values().stream().mapToLong(Long::longValue).sum();
        Map.Entry<String, Long> top = picks.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey)).orElse(null);
        if (top == null || total == 0 || top.getValue() < minPicks) {
            return Optional.empty();
        }
        double share = top.getValue() * 100.0 / total;
        if (share < minSharePct) {
            return Optional.empty();
        }
        return Optional.of(new Habit(top.getKey(), top.getValue(), total, round2(share)));
    }

    // ---- ramp --------------------------------------------------------------------------

    /**
     * How far along the trust ramp an item is at a branch: {@code n ÷ (n + halfPoint)}, never
     * under the launch dial. The dial is what a brand-new item starts at; making it a floor
     * keeps the ramp monotonic, since {@code n ÷ (n + 12)} is below 0.25 for the first three
     * decisions and a price should not retreat because a decision was made.
     */
    public static double maturity(long priorApplied, double halfPoint, double launch) {
        double dial = Stats.clamp(launch, 0, 1);
        if (priorApplied <= 0) {
            return dial;
        }
        return Math.max(dial, priorApplied / (priorApplied + Math.max(1, halfPoint)));
    }

    /**
     * A reproducible variation around the ramp: {@code amplitude × 4m(1−m) × h}, with
     * {@code h} in [−1, 1] from a hash of the salt (item, branch and month), so the same cell
     * in the same month always answers the same and the envelope is zero at both ends.
     */
    public static double wobble(String salt, double amplitude, double maturity) {
        if (amplitude <= 0 || salt == null) {
            return 0;
        }
        int h = salt.hashCode();
        double unit = ((h & 0xffff) / 65535.0) * 2 - 1;
        return amplitude * 4 * maturity * (1 - maturity) * unit;
    }

    // ---- small helpers -----------------------------------------------------------------

    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static String signed(double v) {
        String s = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return v > 0 ? "+" + s : s;
    }
}

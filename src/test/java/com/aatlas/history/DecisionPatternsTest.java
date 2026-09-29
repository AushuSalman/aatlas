package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lean, the habit and the ramp, pinned with the registry defaults: window 180 days,
 * half-life 60 days, 3 decisions needed, deadband 1%, max move 2%.
 */
class DecisionPatternsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final BigDecimal SUGGESTED = new BigDecimal("100.00");
    private static final PricingModel.Config DEFAULTS = PricingModel.Config.defaults();

    /** A row {@code ageDays} old whose actual price was {@code deviationPct} from the suggestion. */
    private static DecisionPatterns.Acceptance row(int ageDays, double deviationPct, String basis) {
        BigDecimal actual = SUGGESTED.multiply(BigDecimal.valueOf(1 + deviationPct / 100));
        return new DecisionPatterns.Acceptance(TODAY.minusDays(ageDays), SUGGESTED, actual, basis);
    }

    private static List<DecisionPatterns.Acceptance> rows(int n, int ageDays, double deviationPct, String basis) {
        List<DecisionPatterns.Acceptance> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(row(ageDays, deviationPct, basis));
        }
        return out;
    }

    // ---- learn -------------------------------------------------------------------------

    @Test
    @DisplayName("no rows at all: none, with a note")
    void noRows() {
        DecisionPatterns.Learning l = DecisionPatterns.learn(List.of(), TODAY, DEFAULTS);
        assertThat(l.available()).isFalse();
        assertThat(l.movePct()).isZero();
        assertThat(l.note()).isEqualTo("No decisions recorded yet.");
    }

    @Test
    @DisplayName("fewer than minDecisions usable rows: none, saying how many are needed")
    void belowMinDecisions() {
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows(2, 0, -3, DecisionPatterns.Acceptance.TENANT),
                TODAY, DEFAULTS);
        assertThat(l.available()).isFalse();
        assertThat(l.decisions()).isEqualTo(2);
        assertThat(l.movePct()).isZero();
        assertThat(l.note()).isEqualTo("2 decisions in the window; 3 needed.");
    }

    @Test
    @DisplayName("a deviation wider than ±50% is a different product, not a preference, and is ignored")
    void outliersAreIgnored() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(3, 0, 60, DecisionPatterns.Acceptance.TENANT));
        rows.addAll(rows(2, 0, -55, DecisionPatterns.Acceptance.TENANT));
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);
        assertThat(l.available()).isFalse();
        assertThat(l.decisions()).isZero();
        assertThat(l.note()).isEqualTo("No usable decisions in the window.");

        // Exactly ±50% still counts.
        DecisionPatterns.Learning edge = DecisionPatterns.learn(rows(3, 0, 50, DecisionPatterns.Acceptance.TENANT),
                TODAY, DEFAULTS);
        assertThat(edge.decisions()).isEqualTo(3);
    }

    @Test
    @DisplayName("a row older than the window is ignored")
    void windowIsRespected() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(3, 181, -3, DecisionPatterns.Acceptance.TENANT));
        rows.addAll(rows(2, 180, -3, DecisionPatterns.Acceptance.TENANT));
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);
        assertThat(l.available()).isFalse();
        assertThat(l.decisions()).isEqualTo(2);
    }

    @Test
    @DisplayName("inside the deadband the price holds: not available, move 0, bias still reported")
    void deadbandHolds() {
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows(4, 0, 0.5, DecisionPatterns.Acceptance.TENANT),
                TODAY, DEFAULTS);
        assertThat(l.available()).isFalse();
        assertThat(l.decisions()).isEqualTo(4);
        assertThat(l.biasPct()).isEqualTo(0.5);
        assertThat(l.movePct()).isZero();
        assertThat(l.note()).isEqualTo("You apply about what is suggested (+0.5% on 4 decisions).");
    }

    @Test
    @DisplayName("a consistent −3% history leans negative: bias −3, capped at maxMove × confidence")
    void consistentNegativeHistoryLeansNegative() {
        // Five fresh rows: weight 1 each, sumW 5, confidence 5 / (5 + 5) = 0.5.
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows(5, 0, -3, DecisionPatterns.Acceptance.TENANT),
                TODAY, DEFAULTS);
        assertThat(l.available()).isTrue();
        assertThat(l.basis()).isEqualTo(DecisionPatterns.Acceptance.TENANT);
        assertThat(l.decisions()).isEqualTo(5);
        assertThat(l.effectiveN()).isEqualTo(5.0);
        assertThat(l.biasPct()).isEqualTo(-3.0);
        assertThat(l.confidence()).isEqualTo(0.5);
        // clamp(−3, −2, 2) = −2, × 0.5.
        assertThat(l.movePct()).isEqualTo(-1.0);
        assertThat(l.note()).isEqualTo("You applied -3% vs the suggestion across 5 decisions (across the business); "
                + "leaning -1%.");
    }

    @Test
    @DisplayName("confidence never exceeds 0.8, so the lean never exceeds maxMove × 0.8")
    void confidenceIsCapped() {
        DecisionPatterns.Learning l = DecisionPatterns.learn(rows(100, 0, -3, DecisionPatterns.Acceptance.TENANT),
                TODAY, DEFAULTS);
        assertThat(l.confidence()).isEqualTo(DecisionPatterns.MAX_CONFIDENCE);
        assertThat(l.movePct()).isEqualTo(-1.6);
        assertThat(Math.abs(l.movePct())).isLessThanOrEqualTo(
                DEFAULTS.number(PricingModel.LEARNING_MAX_MOVE) * DecisionPatterns.MAX_CONFIDENCE);
    }

    @Test
    @DisplayName("item-at-branch rows win over tenant rows when they pass the gate")
    void closestBasisWins() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(3, 0, 4, DecisionPatterns.Acceptance.ITEM_STORE));
        rows.addAll(rows(10, 0, -4, DecisionPatterns.Acceptance.TENANT));

        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);

        assertThat(l.available()).isTrue();
        assertThat(l.basis()).isEqualTo(DecisionPatterns.Acceptance.ITEM_STORE);
        assertThat(l.decisions()).isEqualTo(3);
        assertThat(l.biasPct()).isEqualTo(4.0);
        assertThat(l.movePct()).isPositive();
    }

    @Test
    @DisplayName("item-at-branch rows that fail the gate fall through to the item, then the tenant")
    void fallsThroughWhenTheCloserBasisFails() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(2, 0, 4, DecisionPatterns.Acceptance.ITEM_STORE));
        rows.addAll(rows(10, 0, -4, DecisionPatterns.Acceptance.TENANT));

        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);

        assertThat(l.available()).isTrue();
        assertThat(l.basis()).isEqualTo(DecisionPatterns.Acceptance.TENANT);
        assertThat(l.decisions()).isEqualTo(10);
        assertThat(l.biasPct()).isEqualTo(-4.0);
    }

    @Test
    @DisplayName("when nothing passes, the note is about the widest basis")
    void nothingPassesReportsTenant() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(1, 0, 4, DecisionPatterns.Acceptance.ITEM_STORE));
        rows.addAll(rows(1, 0, -4, DecisionPatterns.Acceptance.TENANT));

        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);

        assertThat(l.available()).isFalse();
        assertThat(l.basis()).isEqualTo(DecisionPatterns.Acceptance.TENANT);
        assertThat(l.decisions()).isEqualTo(2);
    }

    @Test
    @DisplayName("a decision's weight halves every half-life")
    void decayHalvesPerHalfLife() {
        // Ages 0, 60 and 120 days: weights 1, 0.5 and 0.25.
        List<DecisionPatterns.Acceptance> rows = List.of(
                row(0, -3, DecisionPatterns.Acceptance.TENANT),
                row(60, -3, DecisionPatterns.Acceptance.TENANT),
                row(120, -3, DecisionPatterns.Acceptance.TENANT));

        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);

        assertThat(l.decisions()).isEqualTo(3);
        assertThat(l.effectiveN()).isEqualTo(1.75);
        // confidence = 1.75 / 6.75 = 0.259..., rounded to 0.26; move = −2 × 0.259... = −0.52.
        assertThat(l.confidence()).isEqualTo(0.26);
        assertThat(l.movePct()).isEqualTo(-0.52);
    }

    @Test
    @DisplayName("a fresh −5% row outweighs two +5% rows only once they are two half-lives old")
    void freshRowOutweighsOldRowsOnlyWhenTheWeightsSay() {
        // Two +5% rows 120 days old: weight 0.25 each, 0.5 together, against the fresh row's 1.
        // Weighted median: sorted −5 (1), +5 (0.25), +5 (0.25); half of 1.5 is 0.75, reached at −5.
        List<DecisionPatterns.Acceptance> oldRows = List.of(
                row(0, -5, DecisionPatterns.Acceptance.TENANT),
                row(120, 5, DecisionPatterns.Acceptance.TENANT),
                row(120, 5, DecisionPatterns.Acceptance.TENANT));
        DecisionPatterns.Learning leansNegative = DecisionPatterns.learn(oldRows, TODAY, DEFAULTS);
        assertThat(leansNegative.available()).isTrue();
        assertThat(leansNegative.biasPct()).isEqualTo(-5.0);
        assertThat(leansNegative.effectiveN()).isEqualTo(1.5);
        // confidence = 1.5 / 6.5 = 0.2307...; move = clamp(−5, −2, 2) × 0.2307... = −0.4615 → −0.46.
        assertThat(leansNegative.confidence()).isEqualTo(0.23);
        assertThat(leansNegative.movePct()).isEqualTo(-0.46);

        // The same two rows 30 days old: weight 0.5^0.5 = 0.7071 each, 1.414 together, which beats 1.
        // sumW 2.414, half 1.207; −5 accumulates only 1, so the median is +5.
        List<DecisionPatterns.Acceptance> newerRows = List.of(
                row(0, -5, DecisionPatterns.Acceptance.TENANT),
                row(30, 5, DecisionPatterns.Acceptance.TENANT),
                row(30, 5, DecisionPatterns.Acceptance.TENANT));
        DecisionPatterns.Learning leansPositive = DecisionPatterns.learn(newerRows, TODAY, DEFAULTS);
        assertThat(leansPositive.available()).isTrue();
        assertThat(leansPositive.biasPct()).isEqualTo(5.0);
        assertThat(leansPositive.effectiveN()).isCloseTo(2.41, within(0.005));
        assertThat(leansPositive.movePct()).isPositive();
    }

    @Test
    @DisplayName("a row with no price, a non-positive price or no basis match is skipped, not a crash")
    void badRowsAreSkipped() {
        List<DecisionPatterns.Acceptance> rows = new ArrayList<>(rows(3, 0, -3, DecisionPatterns.Acceptance.TENANT));
        rows.add(new DecisionPatterns.Acceptance(TODAY, null, SUGGESTED, DecisionPatterns.Acceptance.TENANT));
        rows.add(new DecisionPatterns.Acceptance(TODAY, SUGGESTED, BigDecimal.ZERO, DecisionPatterns.Acceptance.TENANT));
        rows.add(new DecisionPatterns.Acceptance(null, SUGGESTED, SUGGESTED, "other"));

        DecisionPatterns.Learning l = DecisionPatterns.learn(rows, TODAY, DEFAULTS);

        assertThat(l.decisions()).isEqualTo(3);
        assertThat(l.biasPct()).isEqualTo(-3.0);
    }

    // ---- habit -------------------------------------------------------------------------

    @Test
    @DisplayName("a habit needs at least minPicks of the leading strategy")
    void habitNeedsMinPicks() {
        assertThat(DecisionPatterns.habit(Map.of("max-profit", 2L), 3, 60)).isEmpty();
        assertThat(DecisionPatterns.habit(Map.of("max-profit", 3L), 3, 60)).isPresent();
        assertThat(DecisionPatterns.habit(Map.of(), 3, 60)).isEmpty();
        assertThat(DecisionPatterns.habit(null, 3, 60)).isEmpty();
    }

    @Test
    @DisplayName("a habit needs the leading strategy to hold at least minShare of all picks")
    void habitNeedsShare() {
        // 3 of 6 is 50%: a coin toss, not a habit.
        assertThat(DecisionPatterns.habit(Map.of("max-profit", 3L, "balanced", 3L), 3, 60)).isEmpty();
        // 3 of 5 is exactly 60%: counts.
        Optional<DecisionPatterns.Habit> habit = DecisionPatterns.habit(
                Map.of("max-profit", 3L, "balanced", 1L, "fast-movement", 1L), 3, 60);
        assertThat(habit).isPresent();
        assertThat(habit.get().strategyKey()).isEqualTo("max-profit");
        assertThat(habit.get().picks()).isEqualTo(3);
        assertThat(habit.get().total()).isEqualTo(5);
        assertThat(habit.get().sharePct()).isEqualTo(60.0);
        assertThat(habit.get().headlineTier()).isEqualTo("aggressive");

        Optional<DecisionPatterns.Habit> balanced = DecisionPatterns.habit(Map.of("balanced", 4L, "max-profit", 1L), 3, 60);
        assertThat(balanced).isPresent();
        assertThat(balanced.get().headlineTier()).isEqualTo("optimal");
    }

    // ---- ramp --------------------------------------------------------------------------

    @Test
    @DisplayName("maturity is n ÷ (n + halfPoint), never under the launch dial")
    void maturity() {
        assertThat(DecisionPatterns.maturity(0, 12, 0.25)).isEqualTo(0.25);
        assertThat(DecisionPatterns.maturity(12, 12, 0.25)).isEqualTo(0.5);
        // The dial is a floor: the first few decisions must not pull the ramp back below it.
        assertThat(DecisionPatterns.maturity(1, 12, 0.25)).isEqualTo(0.25);
        assertThat(DecisionPatterns.maturity(3, 12, 0.25)).isEqualTo(0.25);
        assertThat(DecisionPatterns.maturity(12, 12, 0.9)).isEqualTo(0.9);
        assertThat(DecisionPatterns.maturity(36, 12, 0)).isEqualTo(0.75);
        // The launch dial is clamped to [0, 1].
        assertThat(DecisionPatterns.maturity(0, 12, 1.5)).isEqualTo(1.0);
        assertThat(DecisionPatterns.maturity(0, 12, -1)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("wobble is zero at both ends of the ramp, reproducible for a salt and bounded by the amplitude")
    void wobble() {
        String salt = "HRD118902|100959|2026-09";
        // Primitive comparison on purpose: the envelope can leave a -0.0 behind.
        assertThat(DecisionPatterns.wobble(salt, 15, 0)).isEqualTo(0.0);
        assertThat(DecisionPatterns.wobble(salt, 15, 1)).isEqualTo(0.0);
        assertThat(DecisionPatterns.wobble(salt, 0, 0.5)).isEqualTo(0.0);
        assertThat(DecisionPatterns.wobble(null, 15, 0.5)).isEqualTo(0.0);

        double first = DecisionPatterns.wobble(salt, 15, 0.5);
        assertThat(DecisionPatterns.wobble(salt, 15, 0.5)).isEqualTo(first);
        assertThat(DecisionPatterns.wobble(new String(salt.toCharArray()), 15, 0.5)).isEqualTo(first);

        for (String s : List.of(salt, "a", "b", "c", "HRD118902|100960|2026-09", "HRD118902|100959|2026-10")) {
            for (double m : new double[] {0.1, 0.25, 0.5, 0.75, 0.9}) {
                assertThat(Math.abs(DecisionPatterns.wobble(s, 15, m))).as("%s at %s", s, m).isLessThanOrEqualTo(15);
            }
        }
        // The envelope 4m(1−m) peaks at m = 0.5, so the same salt wobbles less nearer the ends.
        assertThat(Math.abs(DecisionPatterns.wobble(salt, 15, 0.1)))
                .isLessThanOrEqualTo(Math.abs(DecisionPatterns.wobble(salt, 15, 0.5)));
    }
}

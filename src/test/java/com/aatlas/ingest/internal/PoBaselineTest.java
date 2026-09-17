package com.aatlas.ingest.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec A 3.6a, pinned: the numbers the tenant-wide window UPDATE must reproduce for a PO
 * with three earlier orders of the same item in its trailing year.
 */
class PoBaselineTest {

    private static final List<PoBaseline.Prior> PRIORS = List.of(
            new PoBaseline.Prior(LocalDate.of(2026, 1, 15), new BigDecimal("10.00"), 100),
            new PoBaseline.Prior(LocalDate.of(2026, 3, 15), new BigDecimal("9.50"), 100),
            new PoBaseline.Prior(LocalDate.of(2026, 5, 15), new BigDecimal("9.80"), 100));

    @Test
    @DisplayName("target is the best prior landed cost, baseline the weighted average, and both spends follow")
    void laterOrderIsMeasuredAgainstTheTrailingYear() {
        PoBaseline.Result r = PoBaseline.compute(LocalDate.of(2026, 6, 10), new BigDecimal("9.60"), 100, PRIORS);

        assertThat(r.target()).isEqualByComparingTo("9.50");
        assertThat(r.baseline()).isEqualByComparingTo("9.7667");
        assertThat(r.followed()).isFalse();
        assertThat(r.baselineSpend()).isEqualByComparingTo("976.67");
        assertThat(r.saved()).isEqualByComparingTo("16.67");
        assertThat(r.leaked()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a first order is its own baseline and target, followed, with nothing saved or leaked")
    void firstOrderIsItsOwnBaseline() {
        PoBaseline.Result r = PoBaseline.compute(LocalDate.of(2026, 1, 15), new BigDecimal("10.00"), 100, List.of());

        assertThat(r.target()).isEqualByComparingTo("10.00");
        assertThat(r.baseline()).isEqualByComparingTo("10.00");
        assertThat(r.followed()).isTrue();
        assertThat(r.baselineSpend()).isEqualByComparingTo("1000.00");
        assertThat(r.saved()).isEqualByComparingTo("0");
        assertThat(r.leaked()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("orders older than a year, or on the same day, are outside the window")
    void windowIsTrailing365DaysExcludingTheOrderDate() {
        List<PoBaseline.Prior> priors = List.of(
                new PoBaseline.Prior(LocalDate.of(2025, 6, 9), new BigDecimal("5.00"), 100), // 366 days before
                new PoBaseline.Prior(LocalDate.of(2026, 6, 10), new BigDecimal("6.00"), 100)); // same day
        PoBaseline.Result r = PoBaseline.compute(LocalDate.of(2026, 6, 10), new BigDecimal("9.00"), 10, priors);

        assertThat(r.target()).isEqualByComparingTo("9.00");
        assertThat(r.followed()).isTrue();
    }

    @Test
    @DisplayName("within half a percent of the target still counts as followed")
    void followedTolerance() {
        List<PoBaseline.Prior> priors = List.of(
                new PoBaseline.Prior(LocalDate.of(2026, 3, 1), new BigDecimal("10.00"), 50));
        assertThat(PoBaseline.compute(LocalDate.of(2026, 4, 1), new BigDecimal("10.05"), 10, priors).followed()).isTrue();
        assertThat(PoBaseline.compute(LocalDate.of(2026, 4, 1), new BigDecimal("10.06"), 10, priors).followed()).isFalse();
    }
}

package com.aatlas.demandmodel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmoothersTest {

    private static Smoothers.Method method(String key) {
        return Smoothers.METHODS.stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    /** Twelve units every third week: four a week on average. */
    private static double[] everyThirdWeek(int weeks) {
        double[] y = new double[weeks];
        for (int t = 0; t < weeks; t += 3) {
            y[t] = 12;
        }
        return y;
    }

    @Test
    @DisplayName("a level never reads the week it forecasts or anything after it")
    void levelsAreCausal() {
        double[] y = everyThirdWeek(40);
        double[] changed = y.clone();
        for (int t = 30; t < 40; t++) {
            changed[t] = 500;
        }
        for (Smoothers.Method m : Smoothers.METHODS) {
            double[] a = Smoothers.levels(m.key(), y, m.grid()[0]);
            double[] b = Smoothers.levels(m.key(), changed, m.grid()[0]);
            assertThat(a).hasSize(41);
            assertThat(Arrays.copyOf(b, 31)).as(m.key()).containsExactly(Arrays.copyOf(a, 31));
        }
    }

    @Test
    @DisplayName("the first weeks are the plain running mean, for every method")
    void warmUpIsTheRunningMean() {
        double[] y = {8, 0, 4, 0, 0, 12, 0, 0, 6, 0};
        for (Smoothers.Method m : Smoothers.METHODS) {
            double[] levels = Smoothers.levels(m.key(), y, m.grid()[0]);
            assertThat(levels[0]).isZero();
            assertThat(levels[1]).isEqualTo(8);
            assertThat(levels[3]).isEqualTo(4);
            assertThat(levels[Smoothers.WARM_UP]).as(m.key()).isEqualTo(3, within(1e-9));
        }
    }

    @Test
    @DisplayName("on a lumpy item that keeps its rhythm, each method settles near the true weekly mean")
    void settlesOnTheMeanOfALumpySeries() {
        double[] y = everyThirdWeek(90);
        assertThat(Smoothers.levels(Smoothers.SBA, y, new double[] {0.1})[90]).isCloseTo(4, within(0.4));
        assertThat(Smoothers.levels(Smoothers.TSB, y, new double[] {0.1, 0.05})[90]).isCloseTo(4, within(0.8));
        assertThat(Smoothers.levels(Smoothers.SES, y, new double[] {0.05})[90]).isCloseTo(4, within(0.8));
    }

    @Test
    @DisplayName("when an item stops selling, TSB fades towards zero and Croston SBA holds its old level")
    void tsbFadesWhenSalesStop() {
        double[] y = Arrays.copyOf(everyThirdWeek(30), 60);
        double sba = Smoothers.levels(Smoothers.SBA, y, new double[] {0.1})[60];
        double tsb = Smoothers.levels(Smoothers.TSB, y, new double[] {0.1, 0.2})[60];
        assertThat(sba).as("SBA updates only when there is a sale").isGreaterThan(3);
        assertThat(tsb).as("TSB lowers the chance of a sale every empty week").isLessThan(0.1);
    }

    @Test
    @DisplayName("exponential smoothing follows a step up in demand, faster with a larger setting")
    void smoothingFollowsAStep() {
        double[] y = new double[40];
        Arrays.fill(y, 0, 20, 10);
        Arrays.fill(y, 20, 40, 30);
        double slow = Smoothers.levels(Smoothers.SES, y, new double[] {0.05})[40];
        double fast = Smoothers.levels(Smoothers.SES, y, new double[] {0.3})[40];
        assertThat(fast).isCloseTo(30, within(0.1));
        assertThat(slow).isBetween(10.0, fast);
    }

    @Test
    @DisplayName("settings are chosen from the weeks given and nothing after them")
    void settingsIgnoreTheFuture() {
        double[] y = new double[60];
        Arrays.fill(y, 0, 20, 10);
        Arrays.fill(y, 20, 60, 30);
        double[] changed = y.clone();
        Arrays.fill(changed, 40, 60, 0);
        Smoothers.Method ses = method(Smoothers.SES);
        assertThat(Smoothers.best(ses, changed, 40, 4)).containsExactly(Smoothers.best(ses, y, 40, 4));
        // A level that has just stepped up is forecast best by the quickest setting.
        assertThat(Smoothers.best(ses, y, 40, 4)).containsExactly(0.3);
        // With no week past the warm-up to score, the first settings stand.
        assertThat(Smoothers.best(ses, y, 6, 4)).containsExactly(ses.grid()[0]);
    }

    @Test
    @DisplayName("a missing or unknown method reads as the forest, which is what rows stored before the contest were")
    void labels() {
        assertThat(Smoothers.label(null)).isEqualTo("Aatlas Market 1.5");
        assertThat(Smoothers.label(Smoothers.FOREST)).isEqualTo("Aatlas Market 1.5");
        assertThat(Smoothers.label(Smoothers.SBA)).isEqualTo("Aatlas Burst 1.2");
        assertThat(Smoothers.label(Smoothers.TSB)).isEqualTo("Aatlas Burst 1.3");
        assertThat(Smoothers.label(Smoothers.SES)).isEqualTo("Aatlas Pulse 1.0");
    }
}

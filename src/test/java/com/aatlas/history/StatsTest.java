package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    @DisplayName("a clean power law is recovered exactly, with r-squared 1")
    void olsLogLogRecoversTheExponent() {
        double[] prices = new double[8];
        double[] units = new double[8];
        for (int i = 0; i < 8; i++) {
            prices[i] = 10 + i;
            units[i] = 100 * Math.pow(prices[i], -1.5);
        }
        Stats.Ols fit = Stats.olsLogLog(prices, units);

        assertThat(fit.available()).isTrue();
        assertThat(fit.n()).isEqualTo(8);
        assertThat(fit.coefficient()).isCloseTo(-1.5, within(1e-9));
        assertThat(fit.r2()).isCloseTo(1.0, within(1e-9));
        assertThat(fit.stdError()).isCloseTo(0, within(1e-6));
    }

    @Test
    @DisplayName("a list-priced item (no price variance) is not a fit")
    void constantPriceIsUnavailable() {
        double[] prices = {12, 12, 12, 12, 12, 12, 12, 12};
        double[] units = {40, 42, 38, 50, 45, 41, 39, 47};

        assertThat(Stats.olsLogLog(prices, units).available()).isFalse();
    }

    @Test
    @DisplayName("three points are too few to report")
    void threePointsAreUnavailable() {
        double[] prices = {10, 11, 12};
        double[] units = {100, 90, 80};

        Stats.Ols fit = Stats.olsLogLog(prices, units);
        assertThat(fit.available()).isFalse();
        assertThat(fit.n()).isEqualTo(3);
    }

    @Test
    @DisplayName("quantiles interpolate linearly, as percentile_cont does")
    void quantilesInterpolate() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        double[] q = Stats.quantiles(values);
        assertThat(q[0]).isCloseTo(3.25, within(1e-12));
        assertThat(q[1]).isCloseTo(5.5, within(1e-12));
        assertThat(q[2]).isCloseTo(7.75, within(1e-12));
    }

    @Test
    @DisplayName("a single value is every quantile; an empty array is NaN")
    void quantileEdges() {
        assertThat(Stats.quantile(new double[] {4.2}, 0.9)).isEqualTo(4.2);
        double empty = Stats.quantile(new double[0], 0.5);
        assertThat(empty != empty).isTrue();
    }

    @Test
    @DisplayName("weighted average weights by quantity; no weight is NaN")
    void weightedAvg() {
        assertThat(Stats.weightedAvg(new double[] {10, 20}, new double[] {1, 3})).isCloseTo(17.5, within(1e-12));
        double none = Stats.weightedAvg(new double[] {10, 20}, new double[] {0, 0});
        assertThat(none != none).isTrue();
    }

    @Test
    @DisplayName("pooled demeaned regression finds the common slope across items at different levels")
    void pooledDemeaned() {
        // Two items, different price levels, the same elasticity of -2.
        double[][] x = new double[2][6];
        double[][] y = new double[2][6];
        for (int g = 0; g < 2; g++) {
            double level = g == 0 ? 2.0 : 4.0;
            for (int i = 0; i < 6; i++) {
                x[g][i] = level + 0.05 * i;
                y[g][i] = 10 - 2 * x[g][i] + (g == 0 ? 0.0 : 3.0);
            }
        }
        Stats.Ols fit = Stats.pooledDemeaned(x, y);

        assertThat(fit.available()).isTrue();
        assertThat(fit.n()).isEqualTo(12);
        assertThat(fit.coefficient()).isCloseTo(-2, within(1e-9));
        assertThat(fit.r2()).isCloseTo(1, within(1e-9));
    }
}

package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeasonalityTest {

    private static double[] seasonal(int months) {
        double[] units = new double[months];
        for (int m = 0; m < months; m++) {
            units[m] = 100 + 30 * Math.sin(2 * Math.PI * m / 12);
        }
        return units;
    }

    @Test
    @DisplayName("twenty-four months give twelve indices with mean exactly 1")
    void twentyFourMonthsIndexToMeanOne() {
        double[] index = Stats.seasonalIndices(seasonal(24), 1);

        assertThat(index).isNotNull().hasSize(12);
        double sum = 0;
        for (double v : index) {
            sum += v;
        }
        assertThat(sum / 12).isCloseTo(1.0, within(1e-9));
        // March (index 2) sits at the top of the sine, September (index 8) at the bottom.
        assertThat(index[2]).isGreaterThan(index[8]);
    }

    @Test
    @DisplayName("seventeen months are not enough")
    void seventeenMonthsAreUnavailable() {
        assertThat(Stats.seasonalIndices(seasonal(17), 1)).isNull();
    }

    @Test
    @DisplayName("eighteen months with sales spread over fewer than eighteen calendar months are not enough")
    void spanMatters() {
        double[] units = new double[30];
        for (int m = 12; m < 30; m++) {
            units[m] = 50;
        }
        assertThat(Stats.seasonalIndices(units, 1)).isNotNull();
        double[] gappy = new double[30];
        for (int m = 0; m < 30; m++) {
            gappy[m] = m < 16 ? 50 : 0;
        }
        assertThat(Stats.seasonalIndices(gappy, 1)).isNull();
    }

    @Test
    @DisplayName("the first month's calendar position rotates the indices")
    void alignsToTheCalendar() {
        double[] units = new double[24];
        for (int m = 0; m < 24; m++) {
            units[m] = (m % 12 == 0) ? 200 : 100;
        }
        double[] fromJanuary = Stats.seasonalIndices(units, 1);
        double[] fromJuly = Stats.seasonalIndices(units, 7);

        assertThat(fromJanuary[0]).isGreaterThan(fromJanuary[1]);
        assertThat(fromJuly[6]).isGreaterThan(fromJuly[0]);
    }
}

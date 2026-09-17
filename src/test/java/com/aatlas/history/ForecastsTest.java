package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForecastsTest {

    /** {@code 100 + 10m + 20 sin(2πm/12)} for m = 1..n: a trend with a mild season on top. */
    private static double[] trending(int n) {
        double[] series = new double[n];
        for (int i = 0; i < n; i++) {
            int m = i + 1;
            series[i] = 100 + 10 * m + 20 * Math.sin(2 * Math.PI * m / 12);
        }
        return series;
    }

    @Test
    @DisplayName("next month lands within five percent of the trend it was fitted on")
    void nextMonthFollowsTheTrend() {
        Forecasts.Forecast f = Forecasts.seasonalNaive(trending(24), 12, null, -1.2, 0, true);

        assertThat(f.coldStart()).isFalse();
        assertThat(f.months()).isEqualTo(24);
        // The trend line at m = 25 is 350; the model carries no seasonal index, so the sine is noise to it.
        assertThat(f.operational().get(0).p50()).isCloseTo(350, withinPercentage(5));
        assertThat(f.growth()).isGreaterThan(0).isLessThanOrEqualTo(Forecasts.MAX_MONTHLY_GROWTH);
        assertThat(f.trend(6)).isEqualTo("up");
    }

    @Test
    @DisplayName("every point is ordered p10 <= p50 <= p90 and the band widens with the horizon")
    void bandsAreOrderedAndWiden() {
        Forecasts.Forecast f = Forecasts.seasonalNaive(trending(24), 12, null, -1.2, 0, true);

        for (List<Forecasts.Point> horizon : List.of(f.sensing(), f.operational(), f.tactical(), f.strategic())) {
            assertThat(horizon).hasSizeGreaterThanOrEqualTo(5);
            for (Forecasts.Point p : horizon) {
                assertThat(p.p10()).isLessThanOrEqualTo(p.p50());
                assertThat(p.p50()).isLessThanOrEqualTo(p.p90());
            }
        }
        double firstSpread = f.operational().get(0).p90() / f.operational().get(0).p50();
        double lastSpread = f.operational().get(5).p90() / f.operational().get(5).p50();
        assertThat(lastSpread).isGreaterThan(firstSpread);
        assertThat(f.sensing()).hasSize(6);
        assertThat(f.tactical()).hasSize(6);
        assertThat(f.strategic()).hasSize(5);
    }

    @Test
    @DisplayName("fewer than six months since the first sale is a cold start")
    void fivePointsAreAColdStart() {
        Forecasts.Forecast f = Forecasts.seasonalNaive(new double[] {12, 15, 11, 18, 14}, 12, null, -1.2, 0, true);

        assertThat(f.coldStart()).isTrue();
        assertThat(f.months()).isEqualTo(5);
        assertThat(f.accuracy().available()).isFalse();
        assertThat(f.q10()).isEqualTo(Forecasts.FALLBACK_Q10);
        assertThat(f.q90()).isEqualTo(Forecasts.FALLBACK_Q90);
    }

    @Test
    @DisplayName("a long zero prefix does not count as history")
    void leadingZerosAreNotHistory() {
        double[] series = new double[12];
        series[9] = 5;
        series[10] = 6;
        series[11] = 7;
        Forecasts.Forecast f = Forecasts.seasonalNaive(series, 12, null, -1.2, 0, true);

        assertThat(f.months()).isEqualTo(3);
        assertThat(f.coldStart()).isTrue();
    }

    @Test
    @DisplayName("a flat series has no trend, no break and full unexplained weight")
    void flatSeries() {
        double[] flat = new double[24];
        java.util.Arrays.fill(flat, 40);
        Forecasts.Forecast f = Forecasts.seasonalNaive(flat, 6, null, -1.2, 0, true);

        assertThat(f.growth()).isEqualTo(0);
        assertThat(f.structuralBreak()).isFalse();
        assertThat(f.intermittent()).isFalse();
        assertThat(f.operational().get(0).p50()).isEqualTo(40);
        assertThat(f.weights().unexplained()).isEqualTo(100);
        assertThat(f.trend(6)).isEqualTo("flat");
    }

    @Test
    @DisplayName("four zero months in the last twelve is intermittent demand")
    void intermittent() {
        double[] series = new double[24];
        for (int i = 0; i < 24; i++) {
            series[i] = (i >= 12 && i % 3 == 0) ? 0 : 10;
        }
        assertThat(Forecasts.seasonalNaive(series, 12, null, -1.2, 0, true).intermittent()).isTrue();
    }

    @Test
    @DisplayName("a cold-start series is the category scaled to the item's last three months")
    void coldStartSeries() {
        double[] item = {0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 5};
        double[] category = new double[12];
        java.util.Arrays.fill(category, 100);
        double[] scaled = Forecasts.coldStartSeries(item, category);

        assertThat(scaled).isNotNull().hasSize(12);
        assertThat(scaled[11]).isCloseTo(100.0 * 10 / 300, withinPercentage(1e-6));
        assertThat(Forecasts.coldStartSeries(new double[] {0, 0, 0}, category)).isNull();
        assertThat(Forecasts.coldStartSeries(item, new double[] {0, 0, 0})).isNull();
    }
}

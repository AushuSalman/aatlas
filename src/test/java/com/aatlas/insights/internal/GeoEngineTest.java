package com.aatlas.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.insights.internal.GeoEngine.HealthResult;
import com.aatlas.insights.internal.GeoEngine.RegionMetrics;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec 3.10 (branch health) and 3.8 (region headline) as pure functions, independent of any
 * request or database - the two formulas {@code brief-b-insights.md}'s test list asks for.
 */
class GeoEngineTest {

    @Test
    void healthyWhenEveryCheckPasses() {
        HealthResult r = GeoEngine.computeHealth(30.0, 50, 70.0, "Low");
        assertThat(r.status()).isEqualTo("healthy");
        assertThat(r.note()).contains("Margin, pricing accuracy and adoption all where they should be");
    }

    @Test
    void watchWhenHalfTheApplicableChecksFail() {
        // margin fails, pricing accuracy fails, adoption passes, inventory passes -> 2/4 = 0.5
        HealthResult r = GeoEngine.computeHealth(20.0, 10, 70.0, "Low");
        assertThat(r.status()).isEqualTo("watch");
        assertThat(r.note()).containsIgnoringCase("margin is thin");
        assertThat(r.note()).containsIgnoringCase("prices drift from the recommendation");
    }

    @Test
    void attentionWhenMostApplicableChecksFail() {
        HealthResult r = GeoEngine.computeHealth(10.0, 5, 10.0, "High");
        assertThat(r.status()).isEqualTo("attention");
        assertThat(r.note()).contains("inventory is heavy");
    }

    @Test
    void skippedChecksAreNotedNotFailed() {
        // No decisions yet, no stock data on file - only margin/accuracy are applicable, both pass.
        HealthResult r = GeoEngine.computeHealth(30.0, 60, null, null);
        assertThat(r.status()).isEqualTo("healthy");
        assertThat(r.note()).containsIgnoringCase("no decisions yet");
        assertThat(r.note()).containsIgnoringCase("no stock data");
        assertThat(r.note()).doesNotContain("too many recommendations");
        assertThat(r.note()).doesNotContain("inventory is heavy");
    }

    @Test
    void attentionWhenNothingIsMeasurableYet() {
        HealthResult r = GeoEngine.computeHealth(null, null, null, null);
        assertThat(r.status()).isEqualTo("attention");
    }

    @Test
    void growingDemandWinsOverEverythingElseForTheHighestGrowthRegion() {
        List<RegionMetrics> regions = List.of(
                new RegionMetrics("south", pct(12), pct(20), rev(100)),
                new RegionMetrics("west", pct(1), pct(30), rev(200)),
                new RegionMetrics("north", pct(-1), pct(15), rev(50)),
                new RegionMetrics("east", pct(2), pct(10), rev(30)));
        Map<String, String> headlines = GeoEngine.regionHeadlines(regions);
        assertThat(headlines.get("south")).isEqualTo("Growing demand");
        // west has the highest margin among the remaining regions
        assertThat(headlines.get("west")).isEqualTo("High margin");
    }

    @Test
    void softeningDemandAndThinMarginForTheWorstRegions() {
        List<RegionMetrics> regions = List.of(
                new RegionMetrics("south", pct(1), pct(25), rev(500)),
                new RegionMetrics("west", pct(0), pct(28), rev(100)),
                new RegionMetrics("north", pct(-5), pct(12), rev(40)),
                new RegionMetrics("east", pct(0.5), pct(9), rev(20)));
        Map<String, String> headlines = GeoEngine.regionHeadlines(regions);
        assertThat(headlines.get("north")).isEqualTo("Softening demand");
        assertThat(headlines.get("east")).isEqualTo("Thin margin");
        // south has neither an extreme growth nor an extreme margin, but is the largest market
        assertThat(headlines.get("south")).isEqualTo("Largest market");
    }

    @Test
    void aRegionThatLeadsNoMetricIsSteady() {
        List<RegionMetrics> regions = List.of(
                new RegionMetrics("south", pct(10), pct(40), rev(1000)),
                new RegionMetrics("west", pct(1), pct(20), rev(10)),
                new RegionMetrics("north", pct(-10), pct(5), rev(5)),
                new RegionMetrics("east", pct(1.5), pct(21), rev(20)));
        Map<String, String> headlines = GeoEngine.regionHeadlines(regions);
        assertThat(headlines.get("east")).isEqualTo("Steady");
    }

    @Test
    void tiesAreBrokenTowardTheHigherRevenueRegion() {
        List<RegionMetrics> regions = List.of(
                new RegionMetrics("south", pct(8), pct(30), rev(50)),
                new RegionMetrics("west", pct(8), pct(30), rev(500)));
        Map<String, String> headlines = GeoEngine.regionHeadlines(regions);
        assertThat(headlines.get("west")).isEqualTo("Growing demand");
        assertThat(headlines.get("south")).isNotEqualTo("Growing demand");
    }

    private static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal rev(double v) {
        return BigDecimal.valueOf(v);
    }
}

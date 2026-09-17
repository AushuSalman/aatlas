package com.aatlas.prices.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SuggestionEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

    private static final Catalogue.ProductRef VALVE = new Catalogue.ProductRef(UUID.randomUUID(), "NP-200",
            "3/4 IN BRASS BALL VALVE", "3/4 IN BRASS BALL VALVE", "Plumbing", "Fittings", "none", "each", false,
            null, "import");

    private static final Reference.Benchmark FITTINGS = new Reference.Benchmark("Plumbing", "Fittings",
            new BigDecimal("36"), new BigDecimal("28"), new BigDecimal("44"), "Fittings carry the margin.", "subcategory");

    private static final Reference.Commodity NONE = new Reference.Commodity("none", "No commodity exposure",
            BigDecimal.ZERO, TODAY);

    private static Catalogue.StoreRef store(BigDecimal rpp) {
        return new Catalogue.StoreRef(UUID.randomUUID(), "100959", "Hardin Supply - Dallas", "US", "TX", "Dallas-Fort Worth",
                rpp, null, "regular", "south", true, "sample");
    }

    private static Resolved cost(String value) {
        return new Resolved(new BigDecimal(value), Resolved.PRICE_LIST, TODAY.minusDays(2));
    }

    @Test
    @DisplayName("cost 10 at a 36% benchmark with a 104 price parity index suggests 15.30 on the benchmark anchor")
    void benchmarkAnchor() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, store(new BigDecimal("104")), cost("10"), null, List.of(), null, FITTINGS, null, NONE, "USD"));

        // B = 10 / (1 - 0.36) = 15.625; ri = 1 - 0.04 x 0.55 = 0.978; 15.2813 rounded to the 0.05 step.
        assertThat(row.unpriceable()).isFalse();
        assertThat(row.status()).isEqualTo(SuggestionEngine.STATUS_OK);
        assertThat(row.anchor()).isEqualTo("benchmark");
        assertThat(row.basis().get("anchor")).isEqualTo("benchmark");
        assertThat(row.benchmark().price()).isEqualByComparingTo("15.28");
        assertThat(row.benchmark().regionalIndex()).isEqualByComparingTo("0.978");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.30");
        assertThat(row.suggestedMarginPct()).isEqualByComparingTo("34.6");
        assertThat(row.floorPrice()).isEqualByComparingTo("13.89");
        assertThat(row.ceilingPrice()).isEqualByComparingTo("17.86");
        assertThat(row.cost()).isEqualByComparingTo("10.00");
        assertThat(row.costSource()).isEqualTo(Resolved.PRICE_LIST);

        @SuppressWarnings("unchecked")
        Map<String, Object> blend = (Map<String, Object>) row.basis().get("blend");
        assertThat(blend.get("anchor")).isEqualTo("benchmark");
        assertThat(row.basis().get("competitor")).isNull();
        assertThat(row.basis().get("text").toString()).contains("Cost $10.00 (price list)")
                .contains("benchmark margin 36%").contains("Dallas #100959 index ×0.98")
                .contains("rounded to $15.30");
    }

    @Test
    @DisplayName("a competitor median blends 50/50 and becomes the anchor")
    void competitorAnchor() {
        List<CompetitorPrices.Observation> observed = List.of(
                new CompetitorPrices.Observation("Northline Supply", new BigDecimal("16.50"), TODAY.minusDays(10), "south",
                        null, null, true),
                new CompetitorPrices.Observation("Summit Pipe & Supply", new BigDecimal("15.50"), TODAY.minusDays(20),
                        "south", null, null, true),
                new CompetitorPrices.Observation("Meridian", new BigDecimal("40.00"), TODAY.minusDays(5), "west", null,
                        null, false));
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, store(new BigDecimal("104")), cost("10"), null, observed, null, FITTINGS, null, NONE, "USD"));

        // Matched rows only: median 16.00; raw = 0.5 x 15.2813 + 0.5 x 16.00 = 15.6407 -> 15.65.
        assertThat(row.anchor()).isEqualTo("competitor");
        assertThat(row.basis().get("anchor")).isEqualTo("competitor");
        assertThat(row.competitor().median()).isEqualByComparingTo("16.00");
        assertThat(row.competitor().observations()).isEqualTo(2);
        assertThat(row.competitor().regionMatched()).isTrue();
        assertThat(row.competitor().low()).isEqualByComparingTo("15.50");
        assertThat(row.competitor().high()).isEqualByComparingTo("16.50");
        assertThat(row.anchorPrice()).isEqualByComparingTo("16.00");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.65");
    }

    @Test
    @DisplayName("without a competitor, the tenant's own median blends 60/40")
    void peerAnchor() {
        SalesHistory.PriceBand band = new SalesHistory.PriceBand(12, new BigDecimal("14.00"), new BigDecimal("15.00"),
                new BigDecimal("16.00"), new BigDecimal("17.00"), new BigDecimal("18.00"), List.of(), List.of());
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, null, cost("10"), null, List.of(), band, FITTINGS, null, NONE, "USD"));

        // Tenant-wide: no regional index. B = 15.625; raw = 0.6 x 15.625 + 0.4 x 16 = 15.775 -> 15.80.
        assertThat(row.anchor()).isEqualTo("peer");
        assertThat(row.peer().median()).isEqualByComparingTo("16.00");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.80");
    }

    @Test
    @DisplayName("the suggestion never leaves the low/high margin band, rounding back inside")
    void clampedToTheBand() {
        List<CompetitorPrices.Observation> high = List.of(
                new CompetitorPrices.Observation("Northline Supply", new BigDecimal("40.00"), TODAY.minusDays(10), null,
                        null, null, false));
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, null, cost("10"), null, high, null, FITTINGS, null, NONE, "USD"));

        // raw = 0.5 x 15.625 + 0.5 x 40 = 27.81 > ceiling 17.8571 -> rounded down inside the band.
        assertThat(row.suggestedPrice()).isEqualByComparingTo("17.85");
        assertThat(row.suggestedPrice()).isLessThanOrEqualTo(row.ceilingPrice());
    }

    @Test
    @DisplayName("a per-category margin override replaces the benchmark target")
    void marginOverride() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, null, cost("10"), null, List.of(), null, FITTINGS, new BigDecimal("50"), NONE, "USD"));

        // 10 / 0.5 = 20.00; the band stretches to admit the override.
        assertThat(row.benchmark().targetMarginPct()).isEqualByComparingTo("50");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("20.00");
        assertThat(row.suggestedMarginPct()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("commodity drift is applied once, as reference data")
    void commodityDrift() {
        Reference.Commodity copper = new Reference.Commodity("copper", "Copper up on the index", new BigDecimal("6.4"),
                TODAY);
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, null, cost("10"), null, List.of(), null, FITTINGS, null, copper, "USD"));

        // 15.625 x (1 + 6.4 x 0.25 / 100) = 15.875 -> 15.90 on the 0.05 step.
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.90");
        @SuppressWarnings("unchecked")
        Map<String, Object> commodity = (Map<String, Object>) row.basis().get("commodity");
        assertThat(commodity.get("source")).isEqualTo("reference");
        assertThat(commodity.get("asOf")).isEqualTo("2026-09-01");
        assertThat(row.basis().get("text").toString()).contains("copper +6.4% over 90 days (reference, 1 Sep 2026)");
    }

    @Test
    @DisplayName("no cost is needs-cost, never a made-up number")
    void needsCost() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(new SuggestionEngine.Inputs(
                VALVE, null, null, new Resolved(new BigDecimal("18.00"), Resolved.SALES_90D, TODAY), List.of(), null,
                FITTINGS, null, NONE, "USD"));

        assertThat(row.status()).isEqualTo(SuggestionEngine.STATUS_NEEDS_COST);
        assertThat(row.unpriceable()).isTrue();
        assertThat(row.reason()).isEqualTo(SuggestionEngine.REASON_NO_COST);
        assertThat(row.suggestedPrice()).isNull();
        assertThat(row.currentPrice()).isEqualByComparingTo("18.00");
        assertThat(row.currentPriceSource()).isEqualTo(Resolved.SALES_90D);
    }
}

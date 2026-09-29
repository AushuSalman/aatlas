package com.aatlas.prices.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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

    private static final Reference.Commodity COPPER = new Reference.Commodity("copper", "Copper up on the index",
            new BigDecimal("6.4"), TODAY);

    private static final PricingModel.Config DEFAULTS = PricingModel.Config.defaults();

    /** Two region-matched competitors at 16.50 and 15.50 (median 16.00) and one unmatched outlier. */
    private static final List<CompetitorPrices.Observation> TWO_COMPETITORS = List.of(
            new CompetitorPrices.Observation("Northline Supply", new BigDecimal("16.50"), TODAY.minusDays(10), "south",
                    null, null, true),
            new CompetitorPrices.Observation("Summit Pipe & Supply", new BigDecimal("15.50"), TODAY.minusDays(20),
                    "south", null, null, true),
            new CompetitorPrices.Observation("Meridian", new BigDecimal("40.00"), TODAY.minusDays(5), "west", null,
                    null, false));

    private static final SalesHistory.PriceBand OWN_BAND = new SalesHistory.PriceBand(12, new BigDecimal("14.00"),
            new BigDecimal("15.00"), new BigDecimal("16.00"), new BigDecimal("17.00"), new BigDecimal("18.00"),
            List.of(), List.of());

    private static Catalogue.StoreRef store(BigDecimal rpp) {
        return new Catalogue.StoreRef(UUID.randomUUID(), "100959", "Hardin Supply - Dallas", "US", "TX", "Dallas-Fort Worth",
                rpp, null, "regular", "south", true, "sample");
    }

    private static Resolved cost(String value) {
        return new Resolved(new BigDecimal(value), Resolved.PRICE_LIST, TODAY.minusDays(2));
    }

    private static Resolved current(String value) {
        return new Resolved(new BigDecimal(value), Resolved.SALES_90D, TODAY);
    }

    private static List<CompetitorPrices.Observation> one(String price) {
        return List.of(new CompetitorPrices.Observation("Northline Supply", new BigDecimal(price), TODAY.minusDays(10),
                null, null, null, false));
    }

    /** The registry's defaults with the named toggles switched off. */
    private static PricingModel.Config off(String... keys) {
        Map<String, PricingModel.Setting> overrides = new LinkedHashMap<>();
        for (String key : keys) {
            overrides.put(key, PricingModel.Setting.on(false));
        }
        return PricingModel.Config.of(overrides);
    }

    private static PricingModel.Config number(String key, String value) {
        return PricingModel.Config.of(Map.of(key, PricingModel.Setting.value(new BigDecimal(value))));
    }

    private static DecisionPatterns.Learning lean(double movePct) {
        return new DecisionPatterns.Learning(true, 5, 4.2, -2.1, 0.7, movePct, DecisionPatterns.Acceptance.ITEM,
                "You applied -2.1% vs the suggestion across 5 decisions (this item, every branch); leaning "
                        + movePct + "%.");
    }

    private static SuggestionEngine.Inputs inputs(Catalogue.StoreRef store, Resolved cost, Resolved current,
            List<CompetitorPrices.Observation> observed, SalesHistory.PriceBand band, BigDecimal override,
            Reference.Commodity commodity, PricingModel.Config config, DecisionPatterns.Learning learning) {
        return new SuggestionEngine.Inputs(VALVE, store, cost, current, observed, band, FITTINGS, override, commodity,
                "USD", config, learning);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> model(SuggestionEngine.SuggestionRow row) {
        return (Map<String, Object>) row.basis().get("model");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(SuggestionEngine.SuggestionRow row) {
        return (List<Map<String, Object>>) model(row).get("steps");
    }

    private static Map<String, Object> step(SuggestionEngine.SuggestionRow row, String key) {
        return steps(row).stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    private static String status(SuggestionEngine.SuggestionRow row, String key) {
        return (String) step(row, key).get("status");
    }

    // ---- the original chain, with every toggle on ------------------------------------------

    @Test
    @DisplayName("cost 10 at a 36% benchmark with a 104 price parity index suggests 15.30 on the benchmark anchor")
    void benchmarkAnchor() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), null, List.of(), null, null, NONE, DEFAULTS, null));

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

        assertThat(status(row, PricingModel.LOCAL_MARKET)).isEqualTo("applied");
        assertThat(step(row, PricingModel.LOCAL_MARKET).get("note").toString()).contains("104").contains("55%")
                .contains("0.978");
        assertThat(status(row, PricingModel.COMPETITORS)).isEqualTo("skipped");
        assertThat(status(row, PricingModel.BLEND_OWN_PRICE)).isEqualTo("skipped");
    }

    @Test
    @DisplayName("a competitor median blends 50/50 and becomes the anchor")
    void competitorAnchor() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), null, TWO_COMPETITORS, null, null, NONE, DEFAULTS, null));

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
        assertThat(row.basis().get("competitor")).isNotNull();
        assertThat(status(row, PricingModel.COMPETITORS)).isEqualTo("applied");
        assertThat(status(row, PricingModel.BLEND_OWN_PRICE)).isEqualTo("applied");
        assertThat(step(row, PricingModel.BLEND_OWN_PRICE).get("note").toString()).contains("50%");
    }

    @Test
    @DisplayName("without a competitor, the tenant's own median blends 60/40")
    void peerAnchor() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), OWN_BAND, null, NONE, DEFAULTS, null));

        // Tenant-wide: no regional index. B = 15.625; raw = 0.6 x 15.625 + 0.4 x 16 = 15.775 -> 15.80.
        assertThat(row.anchor()).isEqualTo("peer");
        assertThat(row.peer().median()).isEqualByComparingTo("16.00");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.80");
        assertThat(status(row, PricingModel.LOCAL_MARKET)).isEqualTo("skipped");
    }

    @Test
    @DisplayName("the suggestion never leaves the low/high margin band, rounding back inside")
    void clampedToTheBand() {
        // 25.00 is plausible (under twice the 13.89 floor) but pulls the raw price over the ceiling.
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, one("25.00"), null, null, NONE, DEFAULTS, null));

        // raw = 0.5 x 15.625 + 0.5 x 25 = 20.31 > ceiling 17.8571 -> rounded down inside the band.
        assertThat(row.anchor()).isEqualTo("competitor");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("17.85");
        assertThat(row.suggestedPrice()).isLessThanOrEqualTo(row.ceilingPrice());
    }

    @Test
    @DisplayName("a per-category margin override replaces the benchmark target")
    void marginOverride() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, new BigDecimal("50"), NONE, DEFAULTS, null));

        // 10 / 0.5 = 20.00; the band stretches to admit the override.
        assertThat(row.benchmark().targetMarginPct()).isEqualByComparingTo("50");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("20.00");
        assertThat(row.suggestedMarginPct()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("commodity drift is applied once, as reference data")
    void commodityDrift() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, COPPER, DEFAULTS, null));

        // 15.625 x (1 + 6.4 x 0.25 / 100) = 15.875 -> 15.90 on the 0.05 step.
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.90");
        @SuppressWarnings("unchecked")
        Map<String, Object> commodity = (Map<String, Object>) row.basis().get("commodity");
        assertThat(commodity.get("source")).isEqualTo("reference");
        assertThat(commodity.get("asOf")).isEqualTo("2026-09-01");
        assertThat(commodity.get("multiplier")).isEqualTo(new BigDecimal("1.016"));
        assertThat(row.basis().get("text").toString()).contains("copper +6.4% over 90 days (reference, 1 Sep 2026)");
        assertThat(status(row, PricingModel.COMMODITY)).isEqualTo("applied");
        assertThat(step(row, PricingModel.COMMODITY).get("note").toString()).contains("25%");
    }

    @Test
    @DisplayName("no cost is needs-cost, never a made-up number")
    void needsCost() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, null, current("18.00"), List.of(), null, null, NONE, DEFAULTS, null));

        assertThat(row.status()).isEqualTo(SuggestionEngine.STATUS_NEEDS_COST);
        assertThat(row.unpriceable()).isTrue();
        assertThat(row.reason()).isEqualTo(SuggestionEngine.REASON_NO_COST);
        assertThat(row.suggestedPrice()).isNull();
        assertThat(row.basis()).isNull();
        assertThat(row.currentPrice()).isEqualByComparingTo("18.00");
        assertThat(row.currentPriceSource()).isEqualTo(Resolved.SALES_90D);
    }

    @Test
    @DisplayName("a null config reads as the defaults")
    void nullConfigIsDefaults() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), null, List.of(), null, null, NONE, null, null));

        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.30");
    }

    // ---- the model's toggles ---------------------------------------------------------------

    @Test
    @DisplayName("competitors off: the observations stay on the row but the anchor falls through to peer, then benchmark")
    void competitorsOff() {
        SuggestionEngine.SuggestionRow benchmark = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), null, TWO_COMPETITORS, null, null, NONE,
                off(PricingModel.COMPETITORS), null));

        assertThat(benchmark.anchor()).isEqualTo("benchmark");
        assertThat(benchmark.suggestedPrice()).isEqualByComparingTo("15.30");
        assertThat(benchmark.competitor()).isNotNull();
        assertThat(benchmark.competitor().median()).isEqualByComparingTo("16.00");
        assertThat(benchmark.basis().get("competitor")).isNull();
        assertThat(status(benchmark, PricingModel.COMPETITORS)).isEqualTo("off");
        assertThat(step(benchmark, PricingModel.COMPETITORS).get("note").toString()).contains("2 observations");
        assertThat(benchmark.basis().get("text").toString()).doesNotContain("competitor median");

        SuggestionEngine.SuggestionRow peer = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, TWO_COMPETITORS, OWN_BAND, null, NONE, off(PricingModel.COMPETITORS), null));

        assertThat(peer.anchor()).isEqualTo("peer");
        assertThat(peer.suggestedPrice()).isEqualByComparingTo("15.80");
    }

    @Test
    @DisplayName("an implausible competitor median (over twice the minimum-margin price) is dropped and noted")
    void implausibleCompetitorDropped() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, one("40.00"), null, null, NONE, DEFAULTS, null));

        // Floor 13.89; 40 > 27.78, so the benchmark alone: 15.625 -> 15.65.
        assertThat(row.anchor()).isEqualTo("benchmark");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(row.competitor().median()).isEqualByComparingTo("40.00");
        assertThat(row.basis().get("competitor")).isNull();
        assertThat(status(row, PricingModel.COMPETITORS)).isEqualTo("skipped");
        assertThat(step(row, PricingModel.COMPETITORS).get("note").toString()).contains("$40.00").contains("$13.89")
                .contains("ignored");

        SuggestionEngine.SuggestionRow kept = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, one("40.00"), null, null, NONE, off(PricingModel.COMPETITORS_PLAUSIBILITY),
                null));

        // With the check off the old maths runs: blended, then clamped to the ceiling.
        assertThat(kept.anchor()).isEqualTo("competitor");
        assertThat(kept.suggestedPrice()).isEqualByComparingTo("17.85");

        SuggestionEngine.SuggestionRow low = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, one("6.00"), null, null, NONE, DEFAULTS, null));

        assertThat(low.anchor()).isEqualTo("benchmark");
        assertThat(status(low, PricingModel.COMPETITORS)).isEqualTo("skipped");
    }

    @Test
    @DisplayName("blend off: a competitor is noted but the benchmark price stands alone")
    void blendOff() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, TWO_COMPETITORS, null, null, NONE, off(PricingModel.BLEND_OWN_PRICE), null));

        assertThat(row.anchor()).isEqualTo("benchmark");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(row.basis().get("competitor")).isNotNull();
        assertThat(status(row, PricingModel.COMPETITORS)).isEqualTo("applied");
        assertThat(status(row, PricingModel.BLEND_OWN_PRICE)).isEqualTo("off");
        @SuppressWarnings("unchecked")
        Map<String, Object> blend = (Map<String, Object>) row.basis().get("blend");
        assertThat(blend.get("benchmarkWeight")).isEqualTo(BigDecimal.ONE);
        assertThat(row.basis().get("text").toString()).contains("benchmark price alone");
    }

    @Test
    @DisplayName("commodity off: no drift, and the step says so")
    void commodityOff() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, COPPER, off(PricingModel.COMMODITY), null));

        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(row.benchmark().commodityDriftPct()).isEqualByComparingTo("6.4");
        @SuppressWarnings("unchecked")
        Map<String, Object> commodity = (Map<String, Object>) row.basis().get("commodity");
        assertThat(commodity.get("multiplier")).isEqualTo(new BigDecimal("1.000"));
        assertThat(status(row, PricingModel.COMMODITY)).isEqualTo("off");
        assertThat(row.basis().get("text").toString()).doesNotContain("over 90 days");

        // A different pass-through share: 15.625 x (1 + 6.4 x 0.5 / 100) = 16.125 -> 16.15.
        SuggestionEngine.SuggestionRow half = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, COPPER,
                number(PricingModel.COMMODITY_PASS_THROUGH, "50"), null));
        assertThat(half.suggestedPrice()).isEqualByComparingTo("16.15");
    }

    @Test
    @DisplayName("local market off leaves the index at 1; its weight is the model's number")
    void localMarketToggle() {
        Catalogue.StoreRef dallas = store(new BigDecimal("104"));
        SuggestionEngine.SuggestionRow off = SuggestionEngine.suggest(inputs(
                dallas, cost("10"), null, List.of(), null, null, NONE, off(PricingModel.LOCAL_MARKET), null));

        assertThat(off.benchmark().regionalIndex()).isEqualByComparingTo("1.000");
        assertThat(off.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(status(off, PricingModel.LOCAL_MARKET)).isEqualTo("off");
        assertThat(off.basis().get("text").toString()).doesNotContain("index ×");
        @SuppressWarnings("unchecked")
        Map<String, Object> region = (Map<String, Object>) off.basis().get("regionalIndex");
        assertThat(region.get("multiplier")).isEqualTo(new BigDecimal("1.000"));

        // Full weight: 1 - 0.04 x 1 = 0.96; 15.625 x 0.96 = 15.00.
        SuggestionEngine.SuggestionRow full = SuggestionEngine.suggest(inputs(
                dallas, cost("10"), null, List.of(), null, null, NONE,
                number(PricingModel.LOCAL_MARKET_WEIGHT, "100"), null));
        assertThat(full.benchmark().regionalIndex()).isEqualByComparingTo("0.960");
        assertThat(full.suggestedPrice()).isEqualByComparingTo("15.00");

        assertThat(SuggestionEngine.regionalIndex(dallas, DEFAULTS)).isEqualByComparingTo("0.978");
        assertThat(SuggestionEngine.regionalIndex(dallas, off(PricingModel.LOCAL_MARKET))).isEqualByComparingTo("1");
        assertThat(SuggestionEngine.regionalIndex(null, DEFAULTS)).isEqualByComparingTo("1");
        assertThat(SuggestionEngine.regionalIndex(store(null), DEFAULTS)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a lean from past decisions moves the raw price before the band, and is written into the basis")
    void learningApplied() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, NONE, DEFAULTS, lean(-1.4)));

        // 15.625 x (1 - 0.014) = 15.40625 -> 15.40; still inside 13.89-17.86.
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.40");
        assertThat(row.suggestedPrice()).isBetween(row.floorPrice(), row.ceilingPrice());
        assertThat(row.basis().get("raw")).isEqualTo(new BigDecimal("15.41"));
        assertThat(status(row, PricingModel.LEARNING)).isEqualTo("applied");
        assertThat(step(row, PricingModel.LEARNING).get("note").toString()).contains("leaning -1.4%");
        assertThat(row.basis().get("text").toString()).contains("; leaning -1.4% from your past decisions");

        SuggestionEngine.SuggestionRow off = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, NONE, off(PricingModel.LEARNING), lean(-1.4)));
        assertThat(off.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(status(off, PricingModel.LEARNING)).isEqualTo("off");
        assertThat(off.basis().get("text").toString()).doesNotContain("leaning");

        SuggestionEngine.SuggestionRow gated = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, NONE, DEFAULTS,
                DecisionPatterns.Learning.none("2 decisions in the window; 3 needed.")));
        assertThat(gated.suggestedPrice()).isEqualByComparingTo("15.65");
        assertThat(status(gated, PricingModel.LEARNING)).isEqualTo("skipped");
        assertThat(step(gated, PricingModel.LEARNING).get("note")).isEqualTo("2 decisions in the window; 3 needed.");

        SuggestionEngine.SuggestionRow none = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, NONE, DEFAULTS, null));
        assertThat(status(none, PricingModel.LEARNING)).isEqualTo("skipped");
        assertThat(step(none, PricingModel.LEARNING).get("note")).isEqualTo("No decisions recorded yet.");
    }

    @Test
    @DisplayName("the plausibility cap lowers the ceiling to a multiple of cost")
    void ceilingCappedAtCostMultiple() {
        // 1.5 x 10 = 15.00 under the 17.86 ceiling; raw 15.81 (competitor 16) is clamped to it.
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, TWO_COMPETITORS, null, null, NONE,
                number(PricingModel.CEILING_COST_MULTIPLE, "1.5"), null));

        assertThat(row.ceilingPrice()).isEqualByComparingTo("15.00");
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.00");
        assertThat(status(row, PricingModel.CEILING_PLAUSIBILITY)).isEqualTo("applied");
        assertThat(step(row, PricingModel.CEILING_PLAUSIBILITY).get("note").toString()).contains("capped")
                .contains("1.5×");
        assertThat(row.basis().get("text").toString()).contains("ceiling held to $15.00");

        SuggestionEngine.SuggestionRow untouched = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, TWO_COMPETITORS, null, null, NONE, DEFAULTS, null));
        assertThat(untouched.ceilingPrice()).isEqualByComparingTo("17.86");
        assertThat(step(untouched, PricingModel.CEILING_PLAUSIBILITY).get("note").toString()).contains("within 4×");

        SuggestionEngine.SuggestionRow off = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, TWO_COMPETITORS, null, null, NONE,
                PricingModel.Config.of(Map.of(PricingModel.CEILING_PLAUSIBILITY, PricingModel.Setting.on(false),
                        PricingModel.CEILING_COST_MULTIPLE, PricingModel.Setting.value(new BigDecimal("1.5")))),
                null));
        assertThat(off.ceilingPrice()).isEqualByComparingTo("17.86");
        assertThat(status(off, PricingModel.CEILING_PLAUSIBILITY)).isEqualTo("off");
    }

    @Test
    @DisplayName("the move cap holds a suggestion within a share of today's price, never under the floor")
    void moveCapBinds() {
        // Cost 8: B = 12.50, floor 11.11, ceiling 14.29. Competitor 15.50 -> raw 14.00.
        List<CompetitorPrices.Observation> competitor = List.of(new CompetitorPrices.Observation("Northline Supply",
                new BigDecimal("15.50"), TODAY.minusDays(10), "south", null, null, true));
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("8"), current("10"), competitor, null, null, NONE, DEFAULTS, null));

        assertThat(row.basis().get("raw")).isEqualTo(new BigDecimal("14.00"));
        assertThat(row.suggestedPrice()).isLessThanOrEqualTo(new BigDecimal("12.50"));
        assertThat(row.suggestedPrice()).isEqualByComparingTo("12.50");
        assertThat(row.suggestedPrice()).isGreaterThanOrEqualTo(row.floorPrice());
        assertThat(status(row, PricingModel.MOVE_CAP)).isEqualTo("applied");
        assertThat(step(row, PricingModel.MOVE_CAP).get("note").toString()).contains("Held within ±25% of today's $10.00")
                .contains("$12.50");
        assertThat(row.basis().get("text").toString()).contains("held within the move cap of today's $10.00");

        // Today's 8.00 caps at 10.00, under the 11.11 floor: the floor wins, rounded up onto it.
        SuggestionEngine.SuggestionRow floored = SuggestionEngine.suggest(inputs(
                null, cost("8"), current("8"), competitor, null, null, NONE, DEFAULTS, null));
        assertThat(floored.suggestedPrice()).isEqualByComparingTo("11.15");
        assertThat(floored.suggestedPrice()).isGreaterThanOrEqualTo(floored.floorPrice());
        assertThat(step(floored, PricingModel.MOVE_CAP).get("note").toString()).contains("lifted to the floor");

        // Wider cap: 50% of 10 admits the raw 14.00.
        SuggestionEngine.SuggestionRow wide = SuggestionEngine.suggest(inputs(
                null, cost("8"), current("10"), competitor, null, null, NONE,
                number(PricingModel.MOVE_CAP_MAX_PCT, "50"), null));
        assertThat(wide.suggestedPrice()).isEqualByComparingTo("14.00");
        assertThat(step(wide, PricingModel.MOVE_CAP).get("note").toString()).contains("nothing to hold");

        SuggestionEngine.SuggestionRow off = SuggestionEngine.suggest(inputs(
                null, cost("8"), current("10"), competitor, null, null, NONE, off(PricingModel.MOVE_CAP), null));
        assertThat(off.suggestedPrice()).isEqualByComparingTo("14.00");
        assertThat(status(off, PricingModel.MOVE_CAP)).isEqualTo("off");

        SuggestionEngine.SuggestionRow noPrice = SuggestionEngine.suggest(inputs(
                null, cost("8"), null, competitor, null, null, NONE, DEFAULTS, null));
        assertThat(noPrice.suggestedPrice()).isEqualByComparingTo("14.00");
        assertThat(status(noPrice, PricingModel.MOVE_CAP)).isEqualTo("skipped");
    }

    @Test
    @DisplayName("rounding off keeps the cent")
    void roundingOff() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), null, List.of(), null, null, NONE,
                off(PricingModel.ROUNDING), null));

        // 15.2813 to the cent, not the 0.05 step.
        assertThat(row.suggestedPrice()).isEqualByComparingTo("15.28");
        assertThat(row.suggestedPrice().scale()).isEqualTo(2);
        assertThat(status(row, PricingModel.ROUNDING)).isEqualTo("off");
        assertThat(row.basis().get("text").toString()).contains("to the cent, $15.28").doesNotContain("rounded to");
    }

    @Test
    @DisplayName("basis.model lists every step the wizard consults, in order, with its status and the toggle count")
    void modelSteps() {
        SuggestionEngine.SuggestionRow row = SuggestionEngine.suggest(inputs(
                null, cost("10"), null, List.of(), null, null, NONE, DEFAULTS, null));

        Map<String, Object> model = model(row);
        assertThat(model.get("on")).isEqualTo(model.get("total"));
        assertThat((int) model.get("total")).isGreaterThan(0);
        assertThat(steps(row)).extracting(s -> s.get("key")).containsExactly(
                PricingModel.COMPETITORS, PricingModel.BLEND_OWN_PRICE, PricingModel.LOCAL_MARKET,
                PricingModel.COMMODITY, PricingModel.LEARNING, PricingModel.CEILING_PLAUSIBILITY,
                PricingModel.MOVE_CAP, PricingModel.ROUNDING);
        assertThat(steps(row)).extracting(s -> s.get("status")).containsExactly(
                "skipped", "skipped", "skipped", "skipped", "skipped", "applied", "skipped", "applied");
        assertThat(steps(row)).allSatisfy(s -> {
            assertThat(s).containsOnlyKeys("key", "label", "status", "note");
            assertThat(s.get("label").toString()).isNotBlank();
            assertThat(s.get("note").toString()).isNotBlank();
        });

        SuggestionEngine.SuggestionRow everything = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), current("15"), TWO_COMPETITORS, OWN_BAND, null, COPPER,
                DEFAULTS, lean(-1.4)));
        assertThat(steps(everything)).extracting(s -> s.get("status")).containsExactly(
                "applied", "applied", "applied", "applied", "applied", "applied", "applied", "applied");

        SuggestionEngine.SuggestionRow nothing = SuggestionEngine.suggest(inputs(
                store(new BigDecimal("104")), cost("10"), current("15"), TWO_COMPETITORS, OWN_BAND, null, COPPER,
                off(PricingModel.COMPETITORS, PricingModel.BLEND_OWN_PRICE, PricingModel.LOCAL_MARKET,
                        PricingModel.COMMODITY, PricingModel.LEARNING, PricingModel.CEILING_PLAUSIBILITY,
                        PricingModel.MOVE_CAP, PricingModel.ROUNDING),
                lean(-1.4)));
        assertThat(steps(nothing)).extracting(s -> s.get("status")).containsOnly("off");
        // Eight switched off, and the toggles that hang off them (competitors.plausibility, learning.strategy) with them.
        assertThat((int) model(nothing).get("on")).isLessThanOrEqualTo((int) model(nothing).get("total") - 8);
        // Every toggle off is the bare benchmark price to the cent: 15.625 -> 15.63.
        assertThat(nothing.anchor()).isEqualTo("benchmark");
        assertThat(nothing.suggestedPrice()).isEqualByComparingTo("15.63");
    }
}

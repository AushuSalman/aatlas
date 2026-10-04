package com.aatlas.buy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.buy.BuyModelSummary;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.MarketEvidence;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.DecisionPatterns.Acceptance;
import com.aatlas.history.PricingModel;
import com.aatlas.history.PricingModel.Config;
import com.aatlas.history.PricingModel.Setting;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The target composition as the buying model configures it: evidence filtering, the market
 * gap, the gap share, never-above-current, the lean, the phase-in, the move cap and the floor
 * at the lowest real price. Pure: points and a track record in, a target out.
 */
class BuyTargetChainTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static MarketEvidence.Point supplier(String name, String landed) {
        return new MarketEvidence.Point(MarketEvidence.SUPPLIER, name + " quote, landed", d(landed),
                "Supplier price plus estimated freight and duty", TODAY);
    }

    private static MarketEvidence.Point bulk(String perUnit) {
        return new MarketEvidence.Point(MarketEvidence.BULK_LOTS, "Bulk lots, per unit", d(perUnit),
                "3 open-market lots, median per unit", TODAY);
    }

    private static MarketEvidence.Point shouldCost(String v) {
        return new MarketEvidence.Point(MarketEvidence.RETAIL_DERIVED, "Should-cost from shop prices", d(v),
                "Median shop price less the target margin", TODAY);
    }

    /** Supplier quotes at 6, 8 and 10: low 6, median 8. */
    private static List<MarketEvidence.Point> panel() {
        return List.of(supplier("Acme", "6"), supplier("Bolt", "8"), supplier("Crate", "10"));
    }

    private static Config cfg(Map<String, Setting> overrides) {
        return Config.of(overrides);
    }

    /** Today's arithmetic with nothing between the composed target and the screen: no phase-in, no cap. */
    private static Map<String, Setting> straight() {
        Map<String, Setting> m = new LinkedHashMap<>();
        m.put(PricingModel.BUY_PHASE_IN, Setting.on(false));
        m.put(PricingModel.BUY_MOVE_CAP, Setting.on(false));
        return m;
    }

    private static Map<String, Setting> with(Map<String, Setting> base, String key, Setting s) {
        Map<String, Setting> m = new LinkedHashMap<>(base);
        m.put(key, s);
        return m;
    }

    private static BuyTargetChain.Result compose(List<MarketEvidence.Point> points, String current,
            BuyTargetChain.Track track, Config cfg) {
        return BuyTargetChain.compose(points, current == null ? null : d(current), track, cfg, "South");
    }

    private static CalcStep step(BuyTargetChain.Result r, String label) {
        return r.steps().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void defaultsReproduceTodaysNumbers() {
        // low 6 + 35% of (8 − 6) = 6.70, under today's 9, so the target is 6.70.
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r.marketLow()).isEqualByComparingTo("6");
        assertThat(r.marketMedian()).isEqualByComparingTo("8");
        assertThat(r.marketHigh()).isEqualByComparingTo("10");
        assertThat(r.target()).isEqualByComparingTo("6.70");
        assertThat(r.fullTarget()).isEqualByComparingTo("6.70");
        assertThat(r.maturity()).isNull();
        assertThat(r.flags()).isEmpty();
        assertThat(r.targetBasis()).isEqualTo("panel");
        assertThat(r.steps().get(r.steps().size() - 1).label()).isEqualTo("Target set at");
        assertThat(r.steps().get(r.steps().size() - 1).kind()).isEqualTo("result");
        assertThat(step(r, "Between the best price and the median").kind()).isEqualTo("applied");
        assertThat(step(r, "Never above what you pay today").kind()).isEqualTo("applied");
        assertThat(step(r, "Phase-in").kind()).isEqualTo("off");
        assertThat(step(r, "Move cap").kind()).isEqualTo("off");
    }

    @Test
    void phaseInStartsHalfwayForANewItem() {
        // Every step on: n = 0 and launch 50% → 9 + 0.5 × (6.70 − 9) = 7.85, inside the 25% cap.
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.target()).isEqualByComparingTo("7.85");
        assertThat(r.fullTarget()).isEqualByComparingTo("6.70");
        assertThat(r.maturity()).isEqualByComparingTo("0.50");
        assertThat(r.flags()).isEmpty();
        assertThat(step(r, "Phase-in").kind()).isEqualTo("applied");
        assertThat(step(r, "Phase-in").note()).startsWith("0 prior decisions");
        assertThat(step(r, "Move cap").kind()).isEqualTo("applied");
    }

    @Test
    void phaseInMaturesWithDecisions() {
        // 6 prior decisions at the half-point of 6 → 0.5 either way; 18 → 18/24 = 0.75.
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(18, null), Config.defaults());
        assertThat(r.maturity()).isEqualByComparingTo("0.75");
        // 9 + 0.75 × (6.70 − 9) = 7.275 → the 25% cap floor is 6.75, so it stands.
        assertThat(r.target()).isEqualByComparingTo("7.2750");
    }

    @Test
    void theDefaultMoveCapBindsWhenThePhaseInIsOff() {
        // 6.70 is a 25.6% drop from 9; with the phase-in off the 25% cap holds it at 6.75.
        Config c = cfg(with(new LinkedHashMap<>(), PricingModel.BUY_PHASE_IN, Setting.on(false)));
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.target()).isEqualByComparingTo("6.75");
        assertThat(r.fullTarget()).isEqualByComparingTo("6.70");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_MOVE_CAPPED);
    }

    @Test
    void gapShareZeroTargetsTheBestPrice() {
        Config c = cfg(with(straight(), PricingModel.BUY_TARGET_GAP_SHARE, Setting.value(d("0"))));
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.target()).isEqualByComparingTo("6.00");
        assertThat(r.flags()).isEmpty();
    }

    @Test
    void gapShareHundredTargetsTheMedian() {
        Config c = cfg(with(straight(), PricingModel.BUY_TARGET_GAP_SHARE, Setting.value(d("100"))));
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.target()).isEqualByComparingTo("8.00");
    }

    @Test
    void targetOffStartsAtTheLowestPrice() {
        Config c = cfg(with(straight(), PricingModel.BUY_TARGET, Setting.on(false)));
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.target()).isEqualByComparingTo("6.00");
        assertThat(step(r, "Between the best price and the median").kind()).isEqualTo("off");
    }

    @Test
    void neverAboveCurrentHoldsAtTodaysCostThenTheFloorCatchesIt() {
        // Today's 5 is under every price on file: held at 5, then floored at the lowest real price, 6.
        BuyTargetChain.Result r = compose(panel(), "5", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r.target()).isEqualByComparingTo("6.00");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_TARGET_AT_FLOOR);
        assertThat(step(r, "Never above what you pay today").note()).contains("held there");
        assertThat(step(r, "Lowest real price").kind()).isEqualTo("step");
    }

    @Test
    void neverAboveCurrentOffLetsTheTargetRise() {
        Config c = cfg(with(straight(), PricingModel.BUY_NEVER_ABOVE_CURRENT, Setting.on(false)));
        BuyTargetChain.Result r = compose(panel(), "5", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.target()).isEqualByComparingTo("6.70");
        assertThat(r.flags()).isEmpty();
        assertThat(step(r, "Never above what you pay today").kind()).isEqualTo("off");
    }

    @Test
    void learningMovesTheTarget() {
        DecisionPatterns.Learning lean = new DecisionPatterns.Learning(true, 5, 4.2, 2.5, 0.8, 2.0,
                Acceptance.ITEM_STORE, "You agreed +2.5% vs the target across 5 decisions; leaning +2%.");
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, lean), cfg(straight()));
        assertThat(r.target()).isEqualByComparingTo("6.8340");
        assertThat(step(r, "Your decisions").kind()).isEqualTo("applied");
        assertThat(step(r, "Your decisions").note()).contains("leaning +2%");

        BuyTargetChain.Result off = compose(panel(), "9", BuyTargetChain.Track.of(0, lean),
                cfg(with(straight(), PricingModel.BUY_LEARNING, Setting.on(false))));
        assertThat(off.target()).isEqualByComparingTo("6.70");
        assertThat(step(off, "Your decisions").kind()).isEqualTo("off");

        BuyTargetChain.Result unavailable = compose(panel(), "9", BuyTargetChain.Track.none(), cfg(straight()));
        assertThat(unavailable.target()).isEqualByComparingTo("6.70");
        assertThat(step(unavailable, "Your decisions").kind()).isEqualTo("skipped");
    }

    @Test
    void theLearnedLeanIsAlsoLearnedFromRealRows() {
        // The chain reads what DecisionPatterns.learn produces from buy deals: agreed 3% over the target.
        List<Acceptance> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(new Acceptance(TODAY.minusDays(i * 10L), d("10"), d("10.30"), Acceptance.ITEM_STORE));
        }
        DecisionPatterns.Learning lean = DecisionPatterns.learn(rows, TODAY, Config.defaults(), PricingModel.Side.BUY);
        assertThat(lean.available()).isTrue();
        assertThat(lean.movePct()).isGreaterThan(0);
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, lean), cfg(straight()));
        assertThat(r.target()).isGreaterThan(d("6.70"));
    }

    @Test
    void moveCapHoldsTheTargetWithinTenPercentOfToday() {
        Map<String, Setting> m = with(straight(), PricingModel.BUY_MOVE_CAP, Setting.on(true));
        m.put(PricingModel.BUY_MOVE_CAP_MAX_PCT, Setting.value(d("10")));
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), cfg(m));
        assertThat(r.target()).isGreaterThanOrEqualTo(d("8.10"));
        assertThat(r.target()).isEqualByComparingTo("8.10");
        assertThat(r.fullTarget()).isEqualByComparingTo("6.70");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_MOVE_CAPPED);
        assertThat(step(r, "Move cap").note()).contains("10%");
    }

    @Test
    void moveCapNeverPushesUnderTheLowestRealPrice() {
        // Today's 5 with the cap at 10% would allow up to 5.50; the floor at 6 wins.
        Map<String, Setting> m = with(straight(), PricingModel.BUY_MOVE_CAP, Setting.on(true));
        m.put(PricingModel.BUY_MOVE_CAP_MAX_PCT, Setting.value(d("10")));
        m.put(PricingModel.BUY_NEVER_ABOVE_CURRENT, Setting.on(false));
        BuyTargetChain.Result r = compose(panel(), "5", BuyTargetChain.Track.of(0, null), cfg(m));
        assertThat(r.target()).isEqualByComparingTo("6.00");
        assertThat(r.flags()).containsExactlyInAnyOrder(BuyModelSummary.FLAG_MOVE_CAPPED,
                BuyModelSummary.FLAG_TARGET_AT_FLOOR);
    }

    @Test
    void plausibilityDropsAMarketPointFarFromTheSupplierQuotes() {
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "6.5"), supplier("Bolt", "7"),
                supplier("Crate", "7.5"), bulk("30.00"));
        BuyTargetChain.Result r = compose(points, "8", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r.points()).hasSize(3);
        assertThat(r.dropped()).extracting(MarketEvidence.Point::value).containsExactly(d("30.00"));
        assertThat(r.marketHigh()).isEqualByComparingTo("7.5");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(r.targetBasis()).isEqualTo("panel");
        CalcStep check = step(r, "Market prices far from your quotes");
        assertThat(check.kind()).isEqualTo("applied");
        assertThat(check.note()).contains("$30.00").contains("over three times");

        // A point under a third goes the same way.
        List<MarketEvidence.Point> low = List.of(supplier("Acme", "6.5"), supplier("Bolt", "7"),
                supplier("Crate", "7.5"), shouldCost("1.50"));
        BuyTargetChain.Result r2 = compose(low, "8", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r2.dropped()).hasSize(1);
        assertThat(r2.marketLow()).isEqualByComparingTo("6.5");
    }

    @Test
    void plausibilityOffKeepsTheFarPoint() {
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "6.5"), supplier("Bolt", "7"),
                supplier("Crate", "7.5"), bulk("30.00"));
        Config c = cfg(with(straight(), PricingModel.BUY_MARKET_PLAUSIBILITY, Setting.on(false)));
        BuyTargetChain.Result r = compose(points, "8", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.points()).hasSize(4);
        assertThat(r.marketHigh()).isEqualByComparingTo("30.00");
        assertThat(r.flags()).isEmpty();
        assertThat(r.targetBasis()).isEqualTo("panel+market");
        assertThat(step(r, "Market prices far from your quotes").kind()).isEqualTo("off");
    }

    @Test
    void plausibilityNeedsSupplierQuotesToCheckAgainst() {
        List<MarketEvidence.Point> points = List.of(bulk("5.00"), shouldCost("30.00"));
        BuyTargetChain.Result r = compose(points, null, BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r.points()).hasSize(2);
        assertThat(r.targetBasis()).isEqualTo("market");
        assertThat(step(r, "Market prices far from your quotes").kind()).isEqualTo("skipped");
        // 5 + 35% of (17.5 − 5) = 9.375; no cost today, so nothing holds it.
        assertThat(r.target()).isEqualByComparingTo("9.375");
        assertThat(step(r, "Never above what you pay today").kind()).isEqualTo("skipped");
    }

    @Test
    void marketOffCountsOnlySupplierQuotes() {
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "6"), supplier("Bolt", "8"),
                supplier("Crate", "10"), bulk("4.00"), shouldCost("5.00"));
        Config c = cfg(with(straight(), PricingModel.BUY_MARKET, Setting.on(false)));
        BuyTargetChain.Result r = compose(points, "9", BuyTargetChain.Track.of(0, null), c);
        assertThat(r.points()).hasSize(3);
        assertThat(r.marketLow()).isEqualByComparingTo("6");
        assertThat(r.target()).isEqualByComparingTo("6.70");
        assertThat(step(r, "Open-market evidence").kind()).isEqualTo("off");
        assertThat(r.steps()).extracting(CalcStep::label).doesNotContain("Bulk lots, per unit",
                "Market prices far from your quotes");

        // With the market on, the bulk lots become the low and the should-cost joins the evidence.
        BuyTargetChain.Result on = compose(points, "9", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(on.points()).hasSize(5);
        assertThat(on.marketLow()).isEqualByComparingTo("4.00");
        assertThat(step(on, "Open-market evidence").kind()).isEqualTo("applied");
        assertThat(on.steps()).extracting(CalcStep::label).contains("Bulk lots, per unit", "Should-cost from shop prices");

        // Only bulk lots off: the should-cost still counts.
        Config bulkOff = cfg(with(straight(), PricingModel.BUY_MARKET_BULK_LOTS, Setting.on(false)));
        BuyTargetChain.Result partial = compose(points, "9", BuyTargetChain.Track.of(0, null), bulkOff);
        assertThat(partial.points()).hasSize(4);
        assertThat(partial.marketLow()).isEqualByComparingTo("5.00");
        assertThat(step(partial, "Open-market evidence").note()).contains("Bulk-lot prices");
    }

    @Test
    void marketOnWithNothingOnFileIsSkipped() {
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(step(r, "Open-market evidence").kind()).isEqualTo("skipped");
    }

    @Test
    void aSinglePriceIsTheTarget() {
        BuyTargetChain.Result r = compose(List.of(supplier("Acme", "7")), "9", BuyTargetChain.Track.of(0, null),
                cfg(straight()));
        assertThat(r.target()).isEqualByComparingTo("7.00");
        assertThat(step(r, "Between the best price and the median").kind()).isEqualTo("skipped");
    }

    @Test
    void noEvidenceMeansNoTarget() {
        BuyTargetChain.Result r = compose(List.of(), "9", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.target()).isNull();
        assertThat(r.fullTarget()).isNull();
        assertThat(r.marketLow()).isNull();
        assertThat(r.targetBasis()).isNull();
        assertThat(r.steps()).extracting(CalcStep::label).doesNotContain("Target set at");
        assertThat(step(r, "Between the best price and the median").kind()).isEqualTo("skipped");
    }

    @Test
    void everyModelStepIsRecordedWithAKind() {
        BuyTargetChain.Result r = compose(panel(), "9", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.steps()).extracting(CalcStep::label).containsSubsequence(
                "Open-market evidence", "Market prices far from your quotes", "Market gap", "Panel quotes, landed here",
                "Market median",
                "Between the best price and the median", "Never above what you pay today", "Your decisions",
                "Phase-in", "Move cap", "Target set at");
        assertThat(r.steps()).extracting(CalcStep::kind)
                .allMatch(k -> List.of("step", "applied", "skipped", "off", "result").contains(k));
    }

    /** The customer's case: suppliers quote around $60 while the open market agrees around $3. */
    private static List<MarketEvidence.Point> farQuotesWithAgreeingMarket() {
        return List.of(supplier("Acme", "58"), supplier("Bolt", "60"), supplier("Crate", "62"), bulk("3.10"),
                shouldCost("2.90"));
    }

    @Test
    void marketGapFollowsAgreeingMarketBelowQuotes() {
        BuyTargetChain.Result r = compose(farQuotesWithAgreeingMarket(), "60", BuyTargetChain.Track.of(0, null),
                Config.defaults());
        // Neither market price is dropped: the two agree with each other (3.10 is 7% above 2.90).
        assertThat(r.dropped()).isEmpty();
        assertThat(r.points()).hasSize(5);
        // The target is composed over the market set alone: 2.90 + 35% of (3.00 − 2.90) = 2.935,
        // shown at once - no phase-in from today's 60, no move cap.
        assertThat(r.marketLow()).isEqualByComparingTo("2.90");
        assertThat(r.marketMedian()).isEqualByComparingTo("3.00");
        assertThat(r.marketHigh()).isEqualByComparingTo("3.10");
        assertThat(r.target()).isEqualByComparingTo("2.935");
        assertThat(r.fullTarget()).isEqualByComparingTo("2.935");
        assertThat(r.maturity()).isNull();
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_MARKET_GAP);
        assertThat(r.targetBasis()).isEqualTo("market");

        BuyTargetChain.MarketGap gap = r.marketGap();
        assertThat(gap).isNotNull();
        assertThat(gap.median()).isEqualByComparingTo("3.00");
        assertThat(gap.low()).isEqualByComparingTo("2.90");
        assertThat(gap.high()).isEqualByComparingTo("3.10");
        assertThat(gap.count()).isEqualTo(2);
        assertThat(gap.gapPct()).isEqualByComparingTo("95");
        assertThat(gap.direction()).isEqualTo("below");

        CalcStep step = step(r, "Market gap");
        assertThat(step.kind()).isEqualTo("applied");
        assertThat(step.value()).isEqualTo("$3.00");
        assertThat(step.note()).isEqualTo("2 open-market prices agree at around $3.00 (from $2.90 to $3.10); "
                + "your suppliers quote $60.00, and the market sits 95% below that. The target follows the market.");
        assertThat(step(r, "Market prices far from your quotes").kind()).isEqualTo("applied");
        assertThat(step(r, "Market prices far from your quotes").value()).isEqualTo("none");
        assertThat(step(r, "Market evidence, per unit").note()).contains("no longer starts from them");
        assertThat(step(r, "Phase-in").kind()).isEqualTo("skipped");
        assertThat(step(r, "Phase-in").note()).contains("follows the market at once");
        assertThat(step(r, "Move cap").kind()).isEqualTo("skipped");
        assertThat(step(r, "Move cap").note()).contains("follows the market at once");
        assertThat(step(r, "Target set at").note()).contains("market gap");
    }

    @Test
    void marketGapWithoutQuotesComparesWithTodaysCost() {
        // No supplier quotes: the market is compared with what you pay today, and still followed at once.
        List<MarketEvidence.Point> points = List.of(bulk("3.10"), shouldCost("2.90"));
        BuyTargetChain.Result r = compose(points, "60", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.target()).isEqualByComparingTo("2.935");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_MARKET_GAP);
        assertThat(r.marketGap().direction()).isEqualTo("below");
        assertThat(step(r, "Market gap").note()).contains("you pay $60.00 today");
        assertThat(step(r, "Phase-in").kind()).isEqualTo("skipped");
        assertThat(step(r, "Move cap").kind()).isEqualTo("skipped");
    }

    @Test
    void loneFarMarketPointStillDropped() {
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "58"), supplier("Bolt", "60"),
                supplier("Crate", "62"), bulk("3.00"));
        BuyTargetChain.Result r = compose(points, "60", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.dropped()).extracting(MarketEvidence.Point::value).containsExactly(d("3.00"));
        assertThat(r.points()).hasSize(3);
        assertThat(r.marketLow()).isEqualByComparingTo("58");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(r.marketGap()).isNull();
        assertThat(r.targetBasis()).isEqualTo("panel");
        CalcStep gap = step(r, "Market gap");
        assertThat(gap.kind()).isEqualTo("skipped");
        assertThat(gap.note()).contains("Only 1 open-market price").contains("2 must agree");
        // The chain otherwise runs as before: 58 + 35% of 2 = 58.70, phased in halfway from 60.
        assertThat(step(r, "Phase-in").kind()).isEqualTo("applied");
        assertThat(r.target()).isEqualByComparingTo("59.35");
    }

    @Test
    void disagreeingMarketPointsAreNotAMarket() {
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "58"), supplier("Bolt", "60"),
                supplier("Crate", "62"), bulk("3.00"), shouldCost("40.00"));
        BuyTargetChain.Result r = compose(points, "60", BuyTargetChain.Track.of(0, null), Config.defaults());
        // Judged one by one as before: 3 is under a third of the suppliers' 60 and goes; 40 stays.
        assertThat(r.dropped()).extracting(MarketEvidence.Point::value).containsExactly(d("3.00"));
        assertThat(r.points()).hasSize(4);
        assertThat(r.marketLow()).isEqualByComparingTo("40.00");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(r.marketGap()).isNull();
        CalcStep gap = step(r, "Market gap");
        assertThat(gap.kind()).isEqualTo("skipped");
        assertThat(gap.note()).contains("disagree with each other").contains("$3.00 to $40.00");
        assertThat(step(r, "Market prices far from your quotes").note()).contains("under a third");
        assertThat(step(r, "Phase-in").kind()).isEqualTo("applied");
    }

    @Test
    void agreeingMarketNearTheQuotesIsNotAGap() {
        // Bulk 4 and should-cost 5 agree with each other, and their median 4.50 is within 50% of the
        // suppliers' 8: no gap, and they simply join the evidence as before (low 4, median 6 → 4.70).
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "6"), supplier("Bolt", "8"),
                supplier("Crate", "10"), bulk("4.00"), shouldCost("5.00"));
        BuyTargetChain.Result r = compose(points, "9", BuyTargetChain.Track.of(0, null), cfg(straight()));
        assertThat(r.marketGap()).isNull();
        assertThat(r.flags()).isEmpty();
        assertThat(r.points()).hasSize(5);
        assertThat(r.target()).isEqualByComparingTo("4.70");
        assertThat(r.targetBasis()).isEqualTo("panel+market");
        CalcStep gap = step(r, "Market gap");
        assertThat(gap.kind()).isEqualTo("skipped");
        assertThat(gap.note()).contains("within 50%");
    }

    @Test
    void marketGapAboveKeepsWhatYouPay() {
        // You pay 3, your one quote is 3, and the open market agrees at 60-62: good news, not a target.
        List<MarketEvidence.Point> points = List.of(supplier("Acme", "3"), bulk("60.00"), shouldCost("62.00"));
        BuyTargetChain.Result r = compose(points, "3", BuyTargetChain.Track.of(0, null), Config.defaults());
        assertThat(r.dropped()).isEmpty();
        assertThat(r.marketMedian()).isEqualByComparingTo("61");
        assertThat(r.target()).isEqualByComparingTo("3.00");
        assertThat(r.fullTarget()).isEqualByComparingTo("3.00");
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_MARKET_GAP);
        assertThat(r.marketGap().direction()).isEqualTo("above");
        assertThat(r.marketGap().gapPct()).isEqualByComparingTo("1933.33");
        assertThat(step(r, "Market gap").kind()).isEqualTo("applied");
        assertThat(step(r, "Market gap").note()).contains("1933% above").contains("Good news");
        assertThat(step(r, "Never above what you pay today").kind()).isEqualTo("applied");
        assertThat(step(r, "Never above what you pay today").note()).contains("good news").contains("held there");
        assertThat(step(r, "Phase-in").kind()).isEqualTo("skipped");
        assertThat(step(r, "Move cap").kind()).isEqualTo("skipped");
        assertThat(step(r, "Target set at").note()).contains("held at what you pay today");

        // With never-above-current off, the target may rise to the market: 60 + 35% of (61 − 60).
        Config rise = cfg(with(new LinkedHashMap<>(), PricingModel.BUY_NEVER_ABOVE_CURRENT, Setting.on(false)));
        BuyTargetChain.Result up = compose(points, "3", BuyTargetChain.Track.of(0, null), rise);
        assertThat(up.target()).isEqualByComparingTo("60.35");
        assertThat(step(up, "Market gap").note()).contains("The target follows the market");
    }

    @Test
    void marketGapOffKeepsOldBehaviour() {
        Config c = cfg(with(straight(), PricingModel.BUY_MARKET_GAP, Setting.on(false)));
        BuyTargetChain.Result r = compose(farQuotesWithAgreeingMarket(), "60", BuyTargetChain.Track.of(0, null), c);
        // Both market prices are under a third of the suppliers' median and go, one by one, as before.
        assertThat(r.dropped()).hasSize(2);
        assertThat(r.points()).hasSize(3);
        assertThat(r.marketGap()).isNull();
        assertThat(r.flags()).containsExactly(BuyModelSummary.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(r.marketLow()).isEqualByComparingTo("58");
        assertThat(r.target()).isEqualByComparingTo("58.70");
        assertThat(step(r, "Market gap").kind()).isEqualTo("off");
        assertThat(step(r, "Market prices far from your quotes").note()).contains("under a third");
    }

    @Test
    void reorderOffIsNull() {
        Config off = cfg(with(new LinkedHashMap<>(), PricingModel.BUY_REORDER, Setting.on(false)));
        assertThat(ReorderEngine.planIfOn(off, d("100"), TODAY, d("70"), "b", 21, "supplier", null, TODAY)).isNull();
        MarketEvidence.Reorder on = ReorderEngine.planIfOn(Config.defaults(), d("100"), TODAY, d("70"), "b", 21,
                "supplier", null, TODAY);
        assertThat(on).isNotNull();
        assertThat(on.reorderPoint()).isEqualByComparingTo("315");
        assertThat(on.coverWeeks()).isEqualTo(8);
    }

    @Test
    void reorderSettingsComeFromTheModel() {
        Map<String, Setting> m = new LinkedHashMap<>();
        m.put(PricingModel.BUY_REORDER_COVER_WEEKS, Setting.value(d("4")));
        m.put(PricingModel.BUY_REORDER_SAFETY_SHARE, Setting.value(d("100")));
        m.put(PricingModel.BUY_REORDER_DEFAULT_LEAD_DAYS, Setting.value(d("28")));
        ReorderEngine.Settings s = ReorderEngine.Settings.of(cfg(m));
        assertThat(s).isEqualTo(new ReorderEngine.Settings(4, 100, 26, 14, 28));
        assertThat(ReorderEngine.Settings.of(Config.defaults())).isEqualTo(ReorderEngine.Settings.DEFAULTS);
    }
}

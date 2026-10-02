package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aatlas.history.PricingMath.Recommendation;
import com.aatlas.history.PricingMath.Step;
import com.aatlas.history.PricingModel.Setting;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configurable chain, {@link PricingMath#recommend}, on a copper elbow at one branch:
 * what the defaults do with it and what each switch changes. Plain arithmetic, no Spring.
 */
class PricingMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    /** min margin 25, max discount 15, max speed premium 8, max market deviation 10. */
    private static final Reference.Guardrails GUARDRAILS =
            new Reference.Guardrails(bd("25"), bd("15"), bd("8"), bd("10"));

    /** Fitted on the item: -1.78, se 0.195, 18 months. */
    private static final SalesHistory.Elasticity COPPER_ELASTICITY =
            new SalesHistory.Elasticity(bd("-1.78"), bd("0.62"), 18, SalesHistory.Elasticity.ITEM, bd("0.195"));

    /** The wobble's envelope at the launch point: amplitude x 4m(1-m) with m = 0.25. */
    private static final double LAUNCH_MATURITY = 0.25;
    private static final double WOBBLE_ENVELOPE = 0.15 * 4 * LAUNCH_MATURITY * (1 - LAUNCH_MATURITY);

    private static final BigDecimal PRICE_STEP = new BigDecimal("0.05");

    /** A copper elbow at Dallas: every field of {@link PricingMath.Inputs}, each case naming only what it changes. */
    private static final class Case {
        BigDecimal cost = bd("6");
        BigDecimal current = bd("10");
        BigDecimal ownRef = bd("10.40");
        Anchor ladderAnchor = null;
        BigDecimal competitorMedian = bd("11");
        int competitorCount = 3;
        BigDecimal competitorLow = null;
        BigDecimal competitorHigh = null;
        BigDecimal peerQ2 = bd("11.75");
        BigDecimal peerQ3 = bd("13.95");
        int peerStores = 6;
        BigDecimal bandQ1 = bd("9.57");
        BigDecimal bandQ3 = bd("12.50");
        long bandN = 18;
        PricingMath.Demand demand = null;
        LocalDate lastSale = null;
        BigDecimal commodityPct90 = null;
        BigDecimal rpp = null;
        SalesHistory.Elasticity elasticity = COPPER_ELASTICITY;
        long ordersAtStore = 6;
        BigDecimal benchmarkTargetMarginPct = bd("40");
        PricingMath.Track track = PricingMath.Track.none();
        String rampSalt = "CU-ELBOW-34|100959|2026-09";

        PricingMath.Inputs build() {
            return new PricingMath.Inputs(cost, current, ownRef, ladderAnchor, competitorMedian, competitorCount,
                    competitorLow, competitorHigh,
                    peerQ2, peerQ3, peerStores, bandQ1, bandQ3, bandN, demand, lastSale, TODAY, commodityPct90, rpp,
                    elasticity, ordersAtStore, benchmarkTargetMarginPct, GUARDRAILS, track, rampSalt);
        }

        Recommendation run(PricingModel.Config config) {
            return PricingMath.recommend(build(), config).orElseThrow();
        }

        Recommendation run() {
            return run(PricingModel.Config.defaults());
        }
    }

    private static PricingModel.Config off(String... keys) {
        Map<String, Setting> raw = new LinkedHashMap<>();
        for (String key : keys) {
            raw.put(key, Setting.on(false));
        }
        return PricingModel.Config.of(raw);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Step step(Recommendation rec, String key) {
        return rec.steps().stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a trained demand model's sensitivity enters step 1 like any measurement: same prior, same weight, named and flagged")
    void demandModelSensitivity() {
        Case c = new Case();
        c.elasticity = new SalesHistory.Elasticity(bd("-2.10"), null, 12, SalesHistory.Elasticity.MODEL, null);
        Recommendation rec = c.run();
        assertThat(rec.flags()).contains(Recommendation.FLAG_DEMAND_MODEL);
        assertThat(rec.steps()).anySatisfy(s -> assertThat(s.note()).contains("Trained demand model")
                .contains("12 months of weekly sales").contains("blended 60/40 with the prior"));

        // Folded with measured outcomes afterwards, it is still the model's figure underneath.
        c.elasticity = new SalesHistory.Elasticity(bd("-1.90"), null, 14, SalesHistory.Elasticity.MODEL + "+outcomes", bd("0.2"));
        assertThat(c.run().flags()).contains(Recommendation.FLAG_DEMAND_MODEL);

        // The monthly regression is worded and flagged as before.
        Recommendation regression = new Case().run();
        assertThat(regression.flags()).doesNotContain(Recommendation.FLAG_DEMAND_MODEL);
        assertThat(regression.steps()).anySatisfy(s -> assertThat(s.note()).contains("Measured").contains("months (item)"));

        // The prior's weight is the tenant's setting: with no history needed, the model's figure stands alone.
        Case trusting = new Case();
        trusting.elasticity = new SalesHistory.Elasticity(bd("-2.10"), null, 12, SalesHistory.Elasticity.MODEL, null);
        Map<String, PricingModel.Setting> raw = new java.util.LinkedHashMap<>();
        raw.put(PricingModel.ELASTICITY_PRIOR_WEIGHT, PricingModel.Setting.value(bd("0")));
        assertThat(trusting.run(PricingModel.Config.of(raw)).steps())
                .anySatisfy(s -> assertThat(s.note()).contains("Trained demand model").contains("blended 100/0"));
    }

    private static boolean onPricePoint(BigDecimal price) {
        return price.remainder(PRICE_STEP).signum() == 0;
    }

    // ---- the defaults on a regular item -------------------------------------------------

    @Test
    @DisplayName("Copper elbow, defaults: internal anchor nudged by the competitor, phased in, inside every band")
    void copperElbowWithTheDefaults() {
        Recommendation rec = new Case().run();

        BigDecimal hardFloor = bd("8.00");
        assertThat(rec.floor()).isEqualByComparingTo(hardFloor);
        assertThat(rec.optimal()).isGreaterThanOrEqualTo(hardFloor);
        assertThat(rec.aggressive()).isGreaterThanOrEqualTo(rec.optimal());
        assertThat(rec.optimal()).isBetween(bd("7.50"), bd("12.50"));
        assertThat(rec.aggressive()).isBetween(bd("7.50"), bd("12.50"));

        assertThat(rec.anchorSource()).isEqualTo("internal-peer");
        assertThat(rec.anchor()).isEqualByComparingTo(bd("11.75"));
        assertThat(rec.externalRole()).isEqualTo("directional_adjust");
        assertThat(rec.targetFloor()).isEqualByComparingTo(bd("9.57"));
        assertThat(rec.ceilingSource()).isEqualTo("peer upper quartile");
        assertThat(rec.ceiling()).isEqualByComparingTo(bd("13.95"));

        assertThat(rec.segment()).isEqualTo("regular");
        assertThat(rec.headlineTier()).isEqualTo("optimal");
        assertThat(rec.elasticityConfidence()).isBetween(0.5, 0.9);
        assertThat(rec.elasticity()).isBetween(bd("-1.78"), bd("-1.2"));

        assertThat(rec.steps()).extracting(Step::key).containsSubsequence(
                PricingModel.ELASTICITY, PricingModel.SEGMENT, PricingModel.COMPETITORS, PricingModel.ANCHOR_INTERNAL,
                PricingModel.CORRIDOR_HISTORY, PricingModel.AGGRESSIVE_PROFIT_MAX, PricingModel.DEMAND,
                PricingModel.COMMODITY, PricingModel.LOCAL_MARKET, PricingModel.LEARNING, PricingModel.TIER_GAP,
                PricingModel.TRUST_RAMP, PricingModel.MOVE_CAP, PricingModel.ROUNDING);
        assertThat(step(rec, PricingModel.ELASTICITY).status()).isEqualTo(Step.APPLIED);
        assertThat(step(rec, PricingModel.COMPETITORS).status()).isEqualTo(Step.APPLIED);
        assertThat(step(rec, PricingModel.DEMAND).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.COMMODITY).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.LOCAL_MARKET).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.LEARNING).status()).isEqualTo(Step.SKIPPED);

        Step phaseIn = step(rec, PricingModel.TRUST_RAMP);
        assertThat(phaseIn.status()).isEqualTo(Step.APPLIED);
        assertThat(phaseIn.note()).contains("maturity");
        assertThat(rec.maturity()).isCloseTo(LAUNCH_MATURITY, within(WOBBLE_ENVELOPE + 1e-9));

        assertThat(rec.flags()).doesNotContain(Recommendation.FLAG_CONSTRAINT_CONFLICT);
        assertThat(onPricePoint(rec.optimal())).isTrue();
        assertThat(onPricePoint(rec.aggressive())).isTrue();
    }

    @Test
    @DisplayName("Phase-in off: the optimal price is the rounded win target")
    void trustRampOffQuotesTheTarget() {
        // The move cap is off too: with it on, the aggressive tier stops at +25% and the tier
        // gap then pulls the optimal tier down with it, so the target would not be quoted as is.
        Recommendation rec = new Case().run(off(PricingModel.TRUST_RAMP, PricingModel.MOVE_CAP));

        assertThat(rec.maturity()).isEqualTo(1);
        assertThat(step(rec, PricingModel.TRUST_RAMP).status()).isEqualTo(Step.OFF);
        assertThat(rec.optimal()).isEqualByComparingTo(PricingMath.roundPricePoint(rec.winTarget()));
    }

    @Test
    @DisplayName("Phase-in off, move cap on: the tiers are still fitted inside the cap")
    void trustRampOffInsideTheMoveCap() {
        Recommendation rec = new Case().run(off(PricingModel.TRUST_RAMP));

        assertThat(rec.optimal()).isBetween(bd("7.50"), bd("12.50"));
        assertThat(rec.aggressive()).isBetween(rec.optimal(), bd("12.50"));
        assertThat(rec.optimal()).isLessThanOrEqualTo(PricingMath.roundPricePoint(rec.winTarget()));
    }

    // ---- the anchor and the competitors ----------------------------------------------------

    @Test
    @DisplayName("Internal anchor off: the ladder anchors on the competitor median")
    void ladderAnchorsOnTheCompetitor() {
        Recommendation rec = new Case().run(off(PricingModel.ANCHOR_INTERNAL));

        assertThat(rec.anchorSource()).isEqualTo(Anchor.COMPETITOR);
        assertThat(rec.anchor()).isEqualByComparingTo(bd("11"));
        assertThat(rec.anchorWeight()).isEqualTo(PricingMath.WEIGHT_COMPETITOR);
        assertThat(rec.externalRole()).isEqualTo("anchor");
        assertThat(step(rec, PricingModel.LOCAL_MARKET).status()).isEqualTo(Step.SKIPPED);
    }

    @Test
    @DisplayName("Competitors off: the step is off, the anchor is the peer median alone")
    void competitorsOff() {
        Recommendation rec = new Case().run(off(PricingModel.COMPETITORS));

        assertThat(step(rec, PricingModel.COMPETITORS).status()).isEqualTo(Step.OFF);
        assertThat(rec.anchorSource()).isEqualTo("internal-peer");
        assertThat(rec.externalRole()).isEqualTo("absent");
        assertThat(rec.base()).isEqualByComparingTo(bd("11.75"));
    }

    @Test
    @DisplayName("A competitor far from the peers is ignored rather than followed")
    void divergentCompetitorIsIgnored() {
        Case c = new Case();
        c.competitorMedian = bd("7");
        Recommendation rec = c.run();

        assertThat(rec.externalRole()).isEqualTo("ignored_divergent");
        assertThat(rec.base()).isEqualByComparingTo(bd("11.75"));
    }

    // ---- learning --------------------------------------------------------------------------

    @Test
    @DisplayName("A lean learned from past decisions moves the price")
    void learningLeansThePrice() {
        PricingModel.Config quotedAtOnce = off(PricingModel.TRUST_RAMP, PricingModel.MOVE_CAP);
        Recommendation without = new Case().run(quotedAtOnce);

        Case c = new Case();
        DecisionPatterns.Learning lean = new DecisionPatterns.Learning(true, 6, 4.2, -2.0, 0.75, -1.5,
                DecisionPatterns.Acceptance.ITEM, "You applied -2% vs the suggestion across 6 decisions; leaning -1.5%.");
        c.track = new PricingMath.Track(0, lean, null);
        Recommendation with = c.run(quotedAtOnce);

        assertThat(step(with, PricingModel.LEARNING).status()).isEqualTo(Step.APPLIED);
        assertThat(with.optimal()).isLessThan(without.optimal());
        assertThat(with.winTarget()).isLessThan(without.winTarget());
    }

    @Test
    @DisplayName("A max-profit habit puts the aggressive tier in the headline")
    void habitChangesTheHeadline() {
        Case c = new Case();
        c.track = new PricingMath.Track(0, null, new DecisionPatterns.Habit("max-profit", 8, 10, 80));
        Recommendation rec = c.run();

        assertThat(rec.headlineTier()).isEqualTo("aggressive");
        assertThat(rec.steps()).extracting(Step::key).contains(PricingModel.LEARNING_STRATEGY);
    }

    // ---- the final band --------------------------------------------------------------------

    @Test
    @DisplayName("Move cap: targets far above today's price stop at +25%, unless the cap is off")
    void moveCapBoundsTheMove() {
        Case c = new Case();
        c.competitorMedian = null;
        c.competitorCount = 0;
        c.peerQ2 = bd("16");
        c.peerQ3 = bd("18");
        c.bandQ1 = bd("15");
        c.bandQ3 = bd("17");
        c.elasticity = SalesHistory.Elasticity.defaultValue();

        Recommendation capped = c.run(off(PricingModel.TRUST_RAMP));
        assertThat(capped.optimal()).isLessThanOrEqualTo(bd("12.50"));
        assertThat(capped.aggressive()).isLessThanOrEqualTo(bd("12.50"));
        assertThat(step(capped, PricingModel.MOVE_CAP).status()).isEqualTo(Step.APPLIED);

        Recommendation free = c.run(off(PricingModel.TRUST_RAMP, PricingModel.MOVE_CAP));
        assertThat(free.optimal()).isGreaterThan(bd("12.50"));
        assertThat(free.aggressive()).isGreaterThan(bd("12.50"));
        assertThat(step(free, PricingModel.MOVE_CAP).status()).isEqualTo(Step.OFF);
    }

    @Test
    @DisplayName("Constraint conflict: a ceiling further than the cap allows still wins")
    void constraintConflictLetsTheCeilingWin() {
        Case c = new Case();
        c.current = bd("40");
        c.ownRef = bd("39");
        c.competitorMedian = null;
        c.competitorCount = 0;
        c.peerQ2 = bd("20");
        c.peerQ3 = bd("22");
        c.bandQ1 = bd("18");
        c.bandQ3 = bd("21");
        Recommendation rec = c.run();

        assertThat(rec.flags()).contains(Recommendation.FLAG_CONSTRAINT_CONFLICT);
        assertThat(rec.ceiling()).isLessThan(bd("30"));
        assertThat(rec.optimal()).isLessThanOrEqualTo(rec.ceiling());
        assertThat(rec.aggressive()).isLessThanOrEqualTo(rec.ceiling());
    }

    // ---- edges -----------------------------------------------------------------------------

    @Test
    @DisplayName("No cost, no anchor, no own reference: not priceable")
    void notPriceableWithoutAnyReference() {
        Case c = new Case();
        c.cost = null;
        c.ownRef = null;
        c.ladderAnchor = null;
        c.competitorMedian = null;
        c.competitorCount = 0;
        c.peerQ2 = null;
        c.peerQ3 = null;
        c.peerStores = 0;
        c.bandQ1 = null;
        c.bandQ3 = null;
        c.bandN = 0;
        c.benchmarkTargetMarginPct = null;

        Optional<Recommendation> rec = PricingMath.recommend(c.build(), PricingModel.Config.defaults());

        assertThat(rec).isEmpty();
    }

    @Test
    @DisplayName("An occasional item leads with the aggressive price")
    void occasionalItemLeadsWithAggressive() {
        Case c = new Case();
        c.ordersAtStore = 2;
        Recommendation rec = c.run();

        assertThat(rec.segment()).isEqualTo("occasional");
        assertThat(rec.headlineTier()).isEqualTo("aggressive");
        assertThat(step(rec, PricingModel.SEGMENT).note()).contains("occasional");
    }

    @Test
    @DisplayName("Segment off: always the optimal price, however rarely the item sells")
    void segmentOffAlwaysLeadsWithOptimal() {
        Case c = new Case();
        c.ordersAtStore = 0;
        Recommendation rec = c.run(off(PricingModel.SEGMENT));

        assertThat(rec.segment()).isEqualTo("regular");
        assertThat(rec.headlineTier()).isEqualTo("optimal");
        assertThat(step(rec, PricingModel.SEGMENT).status()).isEqualTo(Step.OFF);
    }

    @Test
    @DisplayName("Rounding off: to the cent, not to a price point")
    void roundingOffKeepsTheCents() {
        Recommendation rec = new Case().run(off(PricingModel.ROUNDING, PricingModel.TRUST_RAMP));

        assertThat(rec.optimal().scale()).isEqualTo(2);
        assertThat(rec.aggressive().scale()).isEqualTo(2);
        assertThat(step(rec, PricingModel.ROUNDING).status()).isEqualTo(Step.OFF);
        assertThat(onPricePoint(rec.optimal())).isFalse();
    }

    @Test
    @DisplayName("Rounding on: both tiers sit on retail price points")
    void roundingOnUsesPricePoints() {
        Recommendation rec = new Case().run(off(PricingModel.TRUST_RAMP));

        assertThat(onPricePoint(rec.optimal())).isTrue();
        assertThat(onPricePoint(rec.aggressive())).isTrue();
    }

    // ---- live competitor prices on a high-margin line with no history --------------------

    /** The 40A breaker: cost $20.67, selling at $82.02 (74.8% margin), no sales, no other branch. */
    private static Case breaker() {
        Case c = new Case();
        c.cost = bd("20.67");
        c.current = bd("82.02");
        c.ownRef = null;
        c.peerQ2 = null;
        c.peerQ3 = null;
        c.peerStores = 0;
        c.bandQ1 = null;
        c.bandQ3 = null;
        c.bandN = 0;
        c.elasticity = null;
        c.ordersAtStore = 0;
        c.benchmarkTargetMarginPct = bd("30");
        c.rampSalt = "BW311240|200410|2026-09";
        return c;
    }

    @Test
    @DisplayName("A competitor at today's price is a market, however high the margin: it is kept and anchors the price")
    void competitorAtTodaysPriceIsKeptOnAHighMarginLine() {
        Case c = breaker();
        c.competitorMedian = bd("82.39");
        c.competitorCount = 1;
        Recommendation rec = c.run();

        assertThat(rec.flags()).doesNotContain(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(step(rec, PricingModel.COMPETITORS).status()).isEqualTo(Step.APPLIED);
        // Before: the $82.39 was dropped as over twice the $27.56 margin floor and the price fell to $30.45.
        assertThat(rec.optimal()).isBetween(bd("70"), bd("91"));
    }

    @Test
    @DisplayName("With only the cost benchmark, today's price stays within reach: the move cap, not the formula, limits the drop")
    void aBenchmarkAloneCannotCutTodaysPriceBeyondTheMoveCap() {
        Case c = breaker();
        c.competitorMedian = null;
        c.competitorCount = 0;
        Recommendation rec = c.run();

        assertThat(rec.flags()).doesNotContain(Recommendation.FLAG_CONSTRAINT_CONFLICT);
        // The ±25% cap around $82.02 holds: never under $61.52.
        assertThat(rec.optimal()).isGreaterThanOrEqualTo(bd("61.50"));
    }

    @Test
    @DisplayName("A competitor far from today's price is still dropped as another product or pack")
    void aCompetitorFarFromTodaysPriceIsStillDropped() {
        Case c = breaker();
        c.competitorMedian = bd("400");
        c.competitorCount = 1;
        Recommendation rec = c.run();

        assertThat(rec.flags()).contains(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(step(rec, PricingModel.COMPETITORS).note()).contains("your price today");
    }

    // ---- the market gap: an item on file at $3 whose competitors sell at $70 ------------

    /** The customer's case: cost $2, on file at $3, three live listings at $68-$72. */
    private static Case underpriced() {
        Case c = new Case();
        c.cost = bd("2");
        c.current = bd("3");
        c.ownRef = bd("3");
        c.competitorMedian = bd("70");
        c.competitorCount = 3;
        c.competitorLow = bd("68");
        c.competitorHigh = bd("72");
        c.peerQ2 = bd("3.10");
        c.peerQ3 = bd("3.30");
        c.bandQ1 = bd("2.90");
        c.bandQ3 = bd("3.20");
        c.elasticity = SalesHistory.Elasticity.defaultValue();
        return c;
    }

    @Test
    @DisplayName("three competitors agreeing at $70 lead the price of an item on file at $3")
    void marketGapFollowsAgreeingCompetitors() {
        Recommendation rec = underpriced().run();
        assertThat(rec.flags()).contains(Recommendation.FLAG_MARKET_GAP)
                .doesNotContain(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE);
        assertThat(rec.externalRole()).isEqualTo("market_gap");
        assertThat(rec.anchorSource()).isEqualTo(Anchor.COMPETITOR);
        // 3% under the $70 median, reached at once: no phase-in, no move cap, no cost-multiple ceiling.
        assertThat(rec.optimal()).isBetween(bd("66"), bd("70"));
        assertThat(rec.aggressive()).isGreaterThanOrEqualTo(rec.optimal());
        assertThat(rec.ceiling()).isGreaterThanOrEqualTo(bd("70"));
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).status()).isEqualTo(Step.APPLIED);
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).note()).contains("3 competitors agree").contains("$70.00");
        assertThat(step(rec, PricingModel.TRUST_RAMP).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.TRUST_RAMP).note()).contains("Market gap");
        assertThat(step(rec, PricingModel.MOVE_CAP).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.MOVE_CAP).note()).contains("lifted");
    }

    @Test
    @DisplayName("a lone $70 listing against a $3 item is still a wrong match")
    void loneFarCompetitorIsStillDropped() {
        Case c = underpriced();
        c.competitorCount = 1;
        c.competitorLow = bd("70");
        c.competitorHigh = bd("70");
        Recommendation rec = c.run();
        assertThat(rec.flags()).contains(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE)
                .doesNotContain(Recommendation.FLAG_MARKET_GAP);
        assertThat(rec.optimal()).isLessThan(bd("5"));
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).status()).isEqualTo(Step.SKIPPED);
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).note()).contains("Only 1 competitor price");
    }

    @Test
    @DisplayName("competitors that disagree with each other are not a market")
    void disagreeingCompetitorsDoNotLead() {
        Case c = underpriced();
        c.competitorCount = 2;
        c.competitorMedian = bd("45");
        c.competitorLow = bd("20");
        c.competitorHigh = bd("70");
        Recommendation rec = c.run();
        assertThat(rec.flags()).doesNotContain(Recommendation.FLAG_MARKET_GAP);
        assertThat(rec.optimal()).isLessThan(bd("5"));
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).note()).contains("disagree");
    }

    @Test
    @DisplayName("with the market-gap step off, far-away competitors are a wrong match again")
    void marketGapOffKeepsTheOldBehaviour() {
        Recommendation rec = underpriced().run(off(PricingModel.COMPETITORS_MARKET_GAP));
        assertThat(rec.flags()).contains(Recommendation.FLAG_COMPETITOR_IMPLAUSIBLE)
                .doesNotContain(Recommendation.FLAG_MARKET_GAP);
        assertThat(rec.optimal()).isLessThan(bd("5"));
        assertThat(step(rec, PricingModel.COMPETITORS_MARKET_GAP).status()).isEqualTo(Step.OFF);
    }

    @Test
    @DisplayName("the market can also pull an overpriced item down, as far as the margin floor")
    void marketGapBelowFallsTowardTheFloor() {
        Case c = underpriced();
        c.current = bd("70");
        c.ownRef = bd("70");
        c.peerQ2 = bd("69");
        c.peerQ3 = bd("72");
        c.bandQ1 = bd("65");
        c.bandQ3 = bd("72");
        c.competitorMedian = bd("3");
        c.competitorLow = bd("2.90");
        c.competitorHigh = bd("3.10");
        Recommendation rec = c.run();
        assertThat(rec.flags()).contains(Recommendation.FLAG_MARKET_GAP);
        // 3% under $3 is $2.91, above the 25%-margin floor of $2.67; the ±25% move cap is lifted.
        assertThat(rec.optimal()).isBetween(bd("2.67"), bd("4"));
        assertThat(step(rec, PricingModel.MOVE_CAP).status()).isEqualTo(Step.SKIPPED);
    }
}

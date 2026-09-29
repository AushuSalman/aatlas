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
}

package com.aatlas.pricingmodel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.decisions.DecisionOutcomes;
import com.aatlas.history.PricingModel;
import com.aatlas.history.PricingModel.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tuner's rules with fakes underneath: each fires once its evidence gate passes and
 * not below it, values clamp to the registry, a learned key whose gate no longer passes
 * is dropped, every value carries a note, and the buy side learns from buy deals.
 */
class ModelTunerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(86_400);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate SELL_SINCE = TODAY.minusDays(180);
    private static final Side SELL = Side.SELL;
    private static final Side BUY = Side.BUY;

    private PricingModelRepository settings;
    private DealSummaries deals;
    private DecisionOutcomes outcomes;
    private CacheManager caches;
    private Cache cache;
    private JdbcTemplate jdbc;
    private ModelTuner tuner;

    @BeforeEach
    void wire() {
        settings = mock(PricingModelRepository.class);
        deals = mock(DealSummaries.class);
        outcomes = mock(DecisionOutcomes.class);
        caches = mock(CacheManager.class);
        cache = mock(Cache.class);
        jdbc = mock(JdbcTemplate.class);
        when(caches.getCache(CacheNames.PRICING_MODEL)).thenReturn(cache);
        when(settings.findById(TENANT)).thenReturn(Optional.empty());
        when(settings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(0, 0, 0));
        tuner = new ModelTuner(settings, deals, outcomes, caches, jdbc, AatlasClock.fixed(NOW, ZoneOffset.UTC), true);
    }

    /** {@code n} deals dated {@code daysAgo} back, each {@code biasPct} away from what was suggested. */
    private static List<DealSummaries.Acceptance> deals(int n, double biasPct, int daysAgo) {
        BigDecimal suggested = new BigDecimal("100");
        BigDecimal actual = suggested.add(BigDecimal.valueOf(biasPct));
        List<DealSummaries.Acceptance> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new DealSummaries.Acceptance(TODAY.minusDays(daysAgo), "ITEM" + i, "100959", suggested, actual,
                    biasPct == 0));
        }
        return rows;
    }

    private void sellDeals(List<DealSummaries.Acceptance> rows) {
        when(deals.acceptance(eq(DealSummaries.SELL), isNull(), isNull(), eq(SELL_SINCE), eq(ModelTuner.DEAL_ROWS)))
                .thenReturn(rows);
    }

    private void buyDeals(LocalDate since, List<DealSummaries.Acceptance> rows) {
        when(deals.acceptance(eq(DealSummaries.BUY), isNull(), isNull(), eq(since), eq(ModelTuner.DEAL_ROWS)))
                .thenReturn(rows);
    }

    private void measured(String median, int n) {
        when(outcomes.learnedAcrossItems(TODAY))
                .thenReturn(Optional.of(new DecisionOutcomes.Learned(new BigDecimal(median), n)));
    }

    private PricingModelEntity savedRow() {
        ArgumentCaptor<PricingModelEntity> row = ArgumentCaptor.forClass(PricingModelEntity.class);
        verify(settings).saveAndFlush(row.capture());
        return row.getValue();
    }

    private static PricingModel.Setting value(String v) {
        return PricingModel.Setting.value(new BigDecimal(v));
    }

    // ---- sell ----------------------------------------------------------------------------

    @Test
    @DisplayName("every sell rule fires once its gate passes: prior from outcomes, launch and cap from the lean, demand from verdicts")
    void sellRulesFireWhenTheirGatesPass() {
        measured("-1.3712", 9);
        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(2, 5, 1));
        sellDeals(deals(8, 8, 0));

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.side()).isEqualTo("sell");
        assertThat(result.learnedAt()).isEqualTo(NOW);
        assertThat(result.learned()).containsOnlyKeys(PricingModel.ELASTICITY_PRIOR, PricingModel.TRUST_RAMP_LAUNCH,
                PricingModel.LEARNING_MAX_MOVE, PricingModel.DEMAND_MAX_MOVE);
        assertThat(result.learned().get(PricingModel.ELASTICITY_PRIOR).value()).isEqualByComparingTo("-1.37");
        assertThat(result.learned().get(PricingModel.TRUST_RAMP_LAUNCH).value()).isEqualByComparingTo("45");
        assertThat(result.learned().get(PricingModel.LEARNING_MAX_MOVE).value()).isEqualByComparingTo("8");
        assertThat(result.learned().get(PricingModel.DEMAND_MAX_MOVE).value()).isEqualByComparingTo("2");

        // Every learned key explains itself: reason, evidence, from (the default here) and to.
        assertThat(result.notes()).containsOnlyKeys(result.learned().keySet().toArray(new String[0]));
        LearnedNote prior = result.notes().get(PricingModel.ELASTICITY_PRIOR);
        assertThat(prior.reason()).isEqualTo("Measured across 9 applied prices: a 1% price rise cost about 1.37% of sales.");
        assertThat(prior.evidence()).isEqualTo(9);
        assertThat(prior.from()).isEqualByComparingTo("-1.2");
        assertThat(prior.to()).isEqualByComparingTo("-1.37");
        LearnedNote launch = result.notes().get(PricingModel.TRUST_RAMP_LAUNCH);
        assertThat(launch.reason()).contains("well above what is phased in").contains("+8%").contains("8 decisions")
                .contains("start 45% of the way");
        assertThat(launch.evidence()).isEqualTo(8);
        assertThat(launch.from()).isEqualByComparingTo("25");
        assertThat(launch.to()).isEqualByComparingTo("45");
        LearnedNote cap = result.notes().get(PricingModel.LEARNING_MAX_MOVE);
        assertThat(cap.reason()).isEqualTo("Your decisions lean +8%; the lean was capped at 2%, so the cap is now 8%.");
        assertThat(cap.evidence()).isEqualTo(8);
        LearnedNote demand = result.notes().get(PricingModel.DEMAND_MAX_MOVE);
        assertThat(demand.reason()).contains("hurt volume than helped").contains("5 against 2").contains("at most 2%");
        assertThat(demand.evidence()).isEqualTo(8);
        assertThat(demand.from()).isEqualByComparingTo("3");

        // A tenant that never saved gets a row with no hand-set overrides and no author.
        PricingModelEntity row = savedRow();
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getSettings()).isEmpty();
        assertThat(row.getUpdatedBy()).isNull();
        assertThat(row.getLearned()).containsOnlyKeys(result.learned().keySet().toArray(new String[0]));
        assertThat(row.getLearnedNotes()).hasSize(4);
        assertThat(row.getLearnedAt()).isEqualTo(NOW);

        // Both sides' cached views are dropped: one row backs them both.
        verify(cache).evict(TENANT + ":SELL");
        verify(cache).evict(TENANT + ":BUY");
    }

    @Test
    @DisplayName("below the gates nothing is learned: four outcomes, seven verdicts, four decisions")
    void sellRulesStayQuietBelowTheirGates() {
        measured("-1.37", 4);
        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(2, 5, 0));
        sellDeals(deals(4, 8, 0));

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.learned()).isEmpty();
        assertThat(result.notes()).isEmpty();
        PricingModelEntity row = savedRow();
        assertThat(row.getLearned()).isEmpty();
        assertThat(row.getLearnedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("enough decisions but too old to carry confidence: the lean moves nothing")
    void oldDecisionsCarryTooLittleConfidence() {
        // Six decisions 120 days back weigh 0.25 each (half-life 60): confidence 1.5 / 6.5 = 0.23.
        sellDeals(deals(6, 8, 120));

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.learned()).isEmpty();
    }

    @Test
    @DisplayName("applying below what is phased in lowers the launch; the cap waits for eight decisions")
    void launchMovesDownAndTheCapWaitsForEightDecisions() {
        sellDeals(deals(6, -7, 0)); // confidence 6 / 11 = 0.55, but only six decisions

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.learned()).containsOnlyKeys(PricingModel.TRUST_RAMP_LAUNCH);
        assertThat(result.learned().get(PricingModel.TRUST_RAMP_LAUNCH).value()).isEqualByComparingTo("15");
        assertThat(result.notes().get(PricingModel.TRUST_RAMP_LAUNCH).reason())
                .contains("well below what is phased in").contains("-7%").contains("start 15% of the way");
    }

    @Test
    @DisplayName("a learned value never leaves the registry's range")
    void valuesClampToTheRegistry() {
        measured("-4.5", 6);
        sellDeals(deals(9, 12, 0));

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.learned().get(PricingModel.ELASTICITY_PRIOR).value()).isEqualByComparingTo("-3");
        assertThat(result.learned().get(PricingModel.LEARNING_MAX_MOVE).value()).isEqualByComparingTo("10");
        assertThat(result.learned().get(PricingModel.TRUST_RAMP_LAUNCH).value()).isEqualByComparingTo("45");
        assertThat(result.notes().get(PricingModel.LEARNING_MAX_MOVE).reason()).endsWith("so the cap is now 10%.");
        assertThat(result.notes().get(PricingModel.ELASTICITY_PRIOR).to()).isEqualByComparingTo("-3");

        measured("-0.05", 6);
        assertThat(tuner.tune(TENANT, SELL).learned().get(PricingModel.ELASTICITY_PRIOR).value())
                .isEqualByComparingTo("-0.1");
    }

    @Test
    @DisplayName("a run replaces the side's learned map: a key whose gate no longer passes is dropped, the other side is kept, from is the previous learned value")
    void staleKeyIsRemovedAndTheOtherSideKept() {
        PricingModelEntity existing = new PricingModelEntity(TENANT, Map.of(), null, EARLIER);
        existing.learn(SELL, Map.of(
                PricingModel.TRUST_RAMP_LAUNCH, value("45"),
                PricingModel.ELASTICITY_PRIOR, value("-1.5")), Map.of(
                PricingModel.TRUST_RAMP_LAUNCH, new LearnedNote("old", 8, new BigDecimal("25"), new BigDecimal("45")),
                PricingModel.ELASTICITY_PRIOR, new LearnedNote("old", 6, new BigDecimal("-1.2"), new BigDecimal("-1.5"))),
                EARLIER);
        existing.learn(BUY, Map.of(PricingModel.BUY_TARGET_GAP_SHARE, value("50")),
                Map.of(PricingModel.BUY_TARGET_GAP_SHARE, new LearnedNote("buy", 7, new BigDecimal("35"), new BigDecimal("50"))),
                EARLIER);
        when(settings.findById(TENANT)).thenReturn(Optional.of(existing));
        measured("-1.1", 7); // the prior still has evidence; the lean has none this time

        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);

        assertThat(result.learned()).containsOnlyKeys(PricingModel.ELASTICITY_PRIOR);
        LearnedNote prior = result.notes().get(PricingModel.ELASTICITY_PRIOR);
        assertThat(prior.from()).isEqualByComparingTo("-1.5");
        assertThat(prior.to()).isEqualByComparingTo("-1.1");

        PricingModelEntity row = savedRow();
        assertThat(row).isSameAs(existing);
        assertThat(row.getLearned()).containsOnlyKeys(PricingModel.BUY_TARGET_GAP_SHARE, PricingModel.ELASTICITY_PRIOR);
        assertThat(row.getLearnedNotes()).containsOnlyKeys(PricingModel.BUY_TARGET_GAP_SHARE, PricingModel.ELASTICITY_PRIOR);
        assertThat(row.getLearnedNotes().get(PricingModel.BUY_TARGET_GAP_SHARE).reason()).isEqualTo("buy");
        assertThat(row.getLearnedAt()).isEqualTo(NOW);
        // The hand-set stamp is not a retune's to move.
        assertThat(row.getUpdatedAt()).isEqualTo(EARLIER);
    }

    @Test
    @DisplayName("the demand nudge shrinks only when hurt outnumbers worked over eight or more measured")
    void demandNudgeShrinksOnlyWhenPricesHurt() {
        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(8, 2, 0));
        assertThat(tuner.tune(TENANT, SELL).learned()).doesNotContainKey(PricingModel.DEMAND_MAX_MOVE);

        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(5, 4, 0));
        assertThat(tuner.tune(TENANT, SELL).learned()).doesNotContainKey(PricingModel.DEMAND_MAX_MOVE);

        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(3, 4, 0)); // seven measured
        assertThat(tuner.tune(TENANT, SELL).learned()).doesNotContainKey(PricingModel.DEMAND_MAX_MOVE);

        when(outcomes.verdicts(TODAY)).thenReturn(new DecisionOutcomes.Verdicts(3, 4, 1)); // eight
        ModelTuner.TuneResult result = tuner.tune(TENANT, SELL);
        assertThat(result.learned().get(PricingModel.DEMAND_MAX_MOVE).value()).isEqualByComparingTo("2");
        assertThat(result.notes().get(PricingModel.DEMAND_MAX_MOVE).evidence()).isEqualTo(8);
    }

    // ---- buy -----------------------------------------------------------------------------

    @Test
    @DisplayName("the buy side reads buy deals in the buy window, never sell deals or outcomes, and keeps the sell side's learned values")
    void buyRulesUseBuyDealsAndTheBuyWindow() {
        PricingModelEntity existing = new PricingModelEntity(TENANT, Map.of(
                PricingModel.BUY_LEARNING_WINDOW_DAYS, value("90"),
                PricingModel.LEARNING_WINDOW_DAYS, value("30")), UUID.randomUUID(), EARLIER);
        existing.learn(SELL, Map.of(PricingModel.TRUST_RAMP_LAUNCH, value("45")), Map.of(), EARLIER);
        when(settings.findById(TENANT)).thenReturn(Optional.of(existing));
        LocalDate since = TODAY.minusDays(90);
        buyDeals(since, deals(6, 6, 0)); // agreed 6% over the target

        ModelTuner.TuneResult result = tuner.tune(TENANT, BUY);

        assertThat(result.side()).isEqualTo("buy");
        assertThat(result.learned()).containsOnlyKeys(PricingModel.BUY_TARGET_GAP_SHARE, PricingModel.BUY_PHASE_IN_LAUNCH);
        assertThat(result.learned().get(PricingModel.BUY_TARGET_GAP_SHARE).value()).isEqualByComparingTo("50");
        assertThat(result.learned().get(PricingModel.BUY_PHASE_IN_LAUNCH).value()).isEqualByComparingTo("40");
        LearnedNote gap = result.notes().get(PricingModel.BUY_TARGET_GAP_SHARE);
        assertThat(gap.reason()).contains("Agreed costs land above the target").contains("+6%").contains("6 decisions")
                .contains("closer to the market median");
        assertThat(gap.evidence()).isEqualTo(6);
        assertThat(gap.from()).isEqualByComparingTo("35");
        assertThat(result.notes().get(PricingModel.BUY_PHASE_IN_LAUNCH).reason())
                .contains("well above what is phased in").contains("start 40% of the way");

        verify(deals).acceptance(eq(DealSummaries.BUY), isNull(), isNull(), eq(since), eq(ModelTuner.DEAL_ROWS));
        verify(deals, never()).acceptance(eq(DealSummaries.SELL), any(), any(), any(), anyInt());
        verifyNoInteractions(outcomes);

        PricingModelEntity row = savedRow();
        assertThat(row.getLearned()).containsOnlyKeys(PricingModel.TRUST_RAMP_LAUNCH, PricingModel.BUY_TARGET_GAP_SHARE,
                PricingModel.BUY_PHASE_IN_LAUNCH);
        assertThat(row.getSettings()).containsOnlyKeys(PricingModel.BUY_LEARNING_WINDOW_DAYS, PricingModel.LEARNING_WINDOW_DAYS);
    }

    @Test
    @DisplayName("beating the target aims the target lower and starts new items further along; small leans move nothing")
    void buyBeatingTheTargetAimsLower() {
        buyDeals(SELL_SINCE, deals(6, -6, 0));
        ModelTuner.TuneResult beat = tuner.tune(TENANT, BUY);
        assertThat(beat.learned().get(PricingModel.BUY_TARGET_GAP_SHARE).value()).isEqualByComparingTo("25");
        assertThat(beat.learned().get(PricingModel.BUY_PHASE_IN_LAUNCH).value()).isEqualByComparingTo("70");
        assertThat(beat.notes().get(PricingModel.BUY_TARGET_GAP_SHARE).reason()).startsWith("You beat the target");
        assertThat(beat.notes().get(PricingModel.BUY_PHASE_IN_LAUNCH).reason()).contains("start 70% of the way");

        buyDeals(SELL_SINCE, deals(6, -3, 0));
        ModelTuner.TuneResult mild = tuner.tune(TENANT, BUY);
        assertThat(mild.learned()).containsOnlyKeys(PricingModel.BUY_TARGET_GAP_SHARE);
        assertThat(mild.learned().get(PricingModel.BUY_TARGET_GAP_SHARE).value()).isEqualByComparingTo("25");

        buyDeals(SELL_SINCE, deals(6, 2, 0));
        assertThat(tuner.tune(TENANT, BUY).learned()).isEmpty();
    }

    // ---- nightly ---------------------------------------------------------------------------

    @Test
    @DisplayName("the nightly run tunes both sides of every active tenant, and one tenant's failure does not stop the rest")
    void nightlyTunesBothSidesOfEveryActiveTenant() {
        UUID broken = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class))
                .thenReturn(List.of(broken, TENANT, other));
        when(settings.findById(broken)).thenThrow(new IllegalStateException("boom"));
        when(settings.findById(other)).thenReturn(Optional.empty());

        tuner.nightly();

        ArgumentCaptor<PricingModelEntity> rows = ArgumentCaptor.forClass(PricingModelEntity.class);
        verify(settings, times(4)).saveAndFlush(rows.capture());
        assertThat(rows.getAllValues()).extracting(PricingModelEntity::getTenantId).containsOnly(TENANT, other);
        verify(cache, times(2)).evict(TENANT + ":SELL");
        verify(cache, times(2)).evict(other + ":BUY");
    }

    @Test
    @DisplayName("switched off, the nightly run does nothing")
    void nightlyIsOffWhenDisabled() {
        ModelTuner off = new ModelTuner(settings, deals, outcomes, caches, jdbc,
                AatlasClock.fixed(NOW, ZoneOffset.UTC), false);

        off.nightly();

        verifyNoInteractions(jdbc);
        verify(settings, never()).saveAndFlush(any());
    }
}

package com.aatlas.pricingmodel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.PricingModel;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * The write path with fakes underneath: the seat check, validation before normalising,
 * and what reaches the row.
 */
class PricingModelServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final TenantContext.Actor HEAD = new TenantContext.Actor(TENANT, USER, "sales-head");
    private static final TenantContext.Actor SELLER = new TenantContext.Actor(TENANT, USER, "seller");

    private PricingModelRepository settings;
    private PricingModelHistoryRepository history;
    private PolicyReader policy;
    private DealSummaries deals;
    private PricingModelService service;

    @BeforeEach
    void wire() {
        settings = mock(PricingModelRepository.class);
        history = mock(PricingModelHistoryRepository.class);
        policy = mock(PolicyReader.class);
        deals = mock(DealSummaries.class);
        AatlasClock clock = AatlasClock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
        service = new PricingModelService(settings, history, policy, deals, clock);

        when(policy.personaFor(eq(TENANT), eq("sales-head"))).thenReturn(persona("sales-head", true));
        when(policy.personaFor(eq(TENANT), eq("seller"))).thenReturn(persona("seller", false));
        when(settings.findById(TENANT)).thenReturn(Optional.empty());
        when(settings.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(history.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Persona persona(String key, boolean guardrails) {
        return new Persona(key, key, Persona.Side.SELL, guardrails ? Persona.Level.HEAD : Persona.Level.REP,
                List.of("sell"), guardrails, guardrails, null, null, "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Throwable thrown) {
        return (Map<String, Object>) ((ApiException) thrown).details().get("fields");
    }

    // ---- validation --------------------------------------------------------------------

    @Test
    @DisplayName("an unknown key is validation_failed naming that key")
    void unknownKeyIsRejected() {
        Map<String, PricingModel.Setting> requested = Map.of("retired.parameter", PricingModel.Setting.on(false));

        assertThatThrownBy(() -> service.save(TENANT, HEAD, requested))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.code()).isEqualTo("validation_failed");
                    assertThat(fieldsOf(ex)).containsOnlyKeys("retired.parameter");
                    assertThat(fieldsOf(ex).get("retired.parameter")).isEqualTo("Not a pricing-model parameter.");
                });
        verify(settings, never()).saveAndFlush(any());
        verify(history, never()).save(any());
    }

    @Test
    @DisplayName("a number outside its range is validation_failed with the range, not silently clamped")
    void outOfRangeNumberIsRejected() {
        Map<String, PricingModel.Setting> requested = Map.of(
                PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("11")));

        assertThatThrownBy(() -> service.save(TENANT, HEAD, requested))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("validation_failed");
                    assertThat(fieldsOf(ex)).containsOnlyKeys(PricingModel.DEMAND_MAX_MOVE);
                    assertThat(fieldsOf(ex).get(PricingModel.DEMAND_MAX_MOVE)).isEqualTo("Must be between 0 and 10 %.");
                });
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a toggle sent with a value, or a number sent with on, is validation_failed naming the type")
    void wrongFieldForTheTypeIsRejected() {
        Map<String, PricingModel.Setting> requested = new LinkedHashMap<>();
        requested.put(PricingModel.ROUNDING, PricingModel.Setting.value(new BigDecimal("5")));
        requested.put(PricingModel.TIER_GAP_BASE, PricingModel.Setting.on(false));
        requested.put(PricingModel.ELASTICITY, PricingModel.Setting.on(false)); // fine

        assertThatThrownBy(() -> service.save(TENANT, HEAD, requested))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("validation_failed");
                    assertThat(fieldsOf(ex)).containsOnlyKeys(PricingModel.ROUNDING, PricingModel.TIER_GAP_BASE);
                    assertThat((String) fieldsOf(ex).get(PricingModel.ROUNDING)).contains("is a toggle");
                    assertThat((String) fieldsOf(ex).get(PricingModel.TIER_GAP_BASE)).contains("is a number");
                });
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a boundary value is accepted; the range is inclusive")
    void boundaryIsInclusive() {
        PricingModel.Parameter p = PricingModel.parameter(PricingModel.DEMAND_MAX_MOVE).orElseThrow();
        Map<String, PricingModel.Setting> requested = Map.of(
                PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(p.max()));

        PricingModelView view = service.save(TENANT, HEAD, requested);

        assertThat(view.overrides().get(PricingModel.DEMAND_MAX_MOVE).value()).isEqualByComparingTo(p.max());
    }

    // ---- permission --------------------------------------------------------------------

    @Test
    @DisplayName("a seat without guardrails=true is refused with 403 not_allowed before anything is validated")
    void seatWithoutGuardrailsIsRefused() {
        Map<String, PricingModel.Setting> requested = Map.of("retired.parameter", PricingModel.Setting.on(false));

        assertThatThrownBy(() -> service.save(TENANT, SELLER, requested))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.code()).isEqualTo("not_allowed");
                    assertThat(ex.getMessage()).startsWith("Your seat cannot change the pricing model.");
                });
        assertThatThrownBy(() -> service.reset(TENANT, SELLER))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("not_allowed"));
        verify(settings, never()).saveAndFlush(any());
        verify(history, never()).save(any());
    }

    // ---- write -------------------------------------------------------------------------

    @Test
    @DisplayName("a permitted save stores only the overrides, and the history row carries the same map")
    void permittedSaveStoresOnlyOverrides() {
        Map<String, PricingModel.Setting> requested = new LinkedHashMap<>();
        requested.put(PricingModel.ELASTICITY, PricingModel.Setting.on(true));                             // default
        requested.put(PricingModel.DEMAND_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("3")));      // default
        requested.put(PricingModel.ROUNDING, PricingModel.Setting.on(false));                              // changed
        requested.put(PricingModel.LEARNING_MAX_MOVE, PricingModel.Setting.value(new BigDecimal("4.5")));  // changed

        PricingModelView view = service.save(TENANT, HEAD, requested);

        ArgumentCaptor<PricingModelEntity> row = ArgumentCaptor.forClass(PricingModelEntity.class);
        verify(settings).saveAndFlush(row.capture());
        assertThat(row.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(row.getValue().getUpdatedBy()).isEqualTo(USER);
        assertThat(row.getValue().getSettings()).containsOnlyKeys(PricingModel.ROUNDING, PricingModel.LEARNING_MAX_MOVE);
        assertThat(row.getValue().getSettings().get(PricingModel.ROUNDING)).isEqualTo(PricingModel.Setting.on(false));
        assertThat(row.getValue().getSettings().get(PricingModel.LEARNING_MAX_MOVE).value()).isEqualByComparingTo("4.5");

        ArgumentCaptor<PricingModelHistoryEntity> entry = ArgumentCaptor.forClass(PricingModelHistoryEntity.class);
        verify(history).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo("set");
        assertThat(entry.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(entry.getValue().getChangedBy()).isEqualTo(USER);
        assertThat(entry.getValue().getChangedByRole()).isEqualTo("sales-head");
        assertThat(entry.getValue().getChangedAt()).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
        assertThat(entry.getValue().getSnapshot()).containsOnlyKeys(PricingModel.ROUNDING, PricingModel.LEARNING_MAX_MOVE);

        // The view: overrides are what was stored, settings cover every key, the summary counts the toggles.
        assertThat(view.overrides()).containsOnlyKeys(PricingModel.ROUNDING, PricingModel.LEARNING_MAX_MOVE);
        assertThat(view.settings()).hasSize(PricingModel.registry().size());
        assertThat(view.settings().get(PricingModel.ROUNDING).on()).isFalse();
        assertThat(view.settings().get(PricingModel.ELASTICITY).on()).isTrue();
        assertThat(view.summary().total()).isEqualTo(PricingModel.Config.defaults().toggleCount()[1]);
        assertThat(view.summary().on()).isEqualTo(view.summary().total() - 1);
        assertThat(view.groups()).extracting(GroupView::key).containsExactly("measure", "compose", "adjust", "learn", "guard");
        assertThat(view.parameters()).hasSize(PricingModel.registry().size());
        assertThat(view.parameters().get(0).type()).isEqualTo("toggle");
    }

    @Test
    @DisplayName("saving again updates the same row rather than inserting a second")
    void secondSaveUpdatesTheRow() {
        PricingModelEntity existing = new PricingModelEntity(TENANT,
                Map.of(PricingModel.ROUNDING, PricingModel.Setting.on(false)), UUID.randomUUID());
        when(settings.findById(TENANT)).thenReturn(Optional.of(existing));

        service.save(TENANT, HEAD, Map.of(PricingModel.COMMODITY, PricingModel.Setting.on(false)));

        ArgumentCaptor<PricingModelEntity> row = ArgumentCaptor.forClass(PricingModelEntity.class);
        verify(settings).saveAndFlush(row.capture());
        assertThat(row.getValue()).isSameAs(existing);
        assertThat(existing.getSettings()).containsOnlyKeys(PricingModel.COMMODITY);
        assertThat(existing.getUpdatedBy()).isEqualTo(USER);
    }

    @Test
    @DisplayName("reset stores an empty map, writes a reset entry, and the view is back on the defaults")
    void resetStoresEmptyMap() {
        PricingModelEntity existing = new PricingModelEntity(TENANT,
                Map.of(PricingModel.ROUNDING, PricingModel.Setting.on(false)), USER);
        when(settings.findById(TENANT)).thenReturn(Optional.of(existing));

        PricingModelView view = service.reset(TENANT, HEAD);

        assertThat(existing.getSettings()).isEmpty();
        ArgumentCaptor<PricingModelHistoryEntity> entry = ArgumentCaptor.forClass(PricingModelHistoryEntity.class);
        verify(history).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo("reset");
        assertThat(entry.getValue().getSnapshot()).isEmpty();
        assertThat(view.overrides()).isEmpty();
        assertThat(view.summary().on()).isEqualTo(view.summary().total());
    }

    @Test
    @DisplayName("current() answers the defaults for a tenant that never saved, with no updatedAt")
    void currentWithoutARowIsTheDefaults() {
        PricingModelView view = service.current(TENANT);

        assertThat(view.overrides()).isEmpty();
        assertThat(view.updatedAt()).isNull();
        assertThat(view.updatedBy()).isNull();
        assertThat(view.settings().get(PricingModel.LEARNING_WINDOW_DAYS).value()).isEqualByComparingTo("180");
    }

    // ---- learning ----------------------------------------------------------------------

    @Test
    @DisplayName("learning reads the window from the model, maps deals to tenant rows, and reduces them")
    void learningReducesTheTenantsDeals() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        LocalDate since = today.minusDays(180);
        List<DealSummaries.Acceptance> rows = List.of(
                new DealSummaries.Acceptance(today, "HRD118902", "100959", new BigDecimal("100"), new BigDecimal("97"), false),
                new DealSummaries.Acceptance(today.minusDays(1), "HRD118902", "100959", new BigDecimal("100"),
                        new BigDecimal("97"), false),
                new DealSummaries.Acceptance(today.minusDays(2), "PVC220", "100959", new BigDecimal("50"),
                        new BigDecimal("48.5"), false),
                new DealSummaries.Acceptance(today.minusDays(3), "PVC220", "100960", new BigDecimal("50"),
                        new BigDecimal("50"), true));
        when(deals.acceptance(isNull(), isNull(), eq(since), anyInt())).thenReturn(rows);
        when(deals.strategyPicks(since)).thenReturn(Map.of("max-profit", 4L, "balanced", 1L));

        LearningView view = service.learning(TENANT);

        assertThat(view.enabled()).isTrue();
        assertThat(view.strategyEnabled()).isTrue();
        assertThat(view.windowDays()).isEqualTo(180);
        assertThat(view.since()).isEqualTo(since);
        assertThat(view.decisions()).isEqualTo(4);
        assertThat(view.followRatePct()).isEqualTo(25.0);
        assertThat(view.lean().available()).isTrue();
        assertThat(view.lean().basis()).isEqualTo("tenant");
        assertThat(view.lean().biasPct()).isEqualTo(-3.0);
        assertThat(view.strategyPicks()).containsEntry("max-profit", 4L);
        assertThat(view.habit()).isNotNull();
        assertThat(view.habit().strategyKey()).isEqualTo("max-profit");
        assertThat(view.recent()).hasSize(4);
    }

    @Test
    @DisplayName("learning with no deals: none, no follow rate, no habit, and a shorter window when the model says so")
    void learningWithNothing() {
        PricingModelEntity existing = new PricingModelEntity(TENANT, Map.of(
                PricingModel.LEARNING, PricingModel.Setting.on(false),
                PricingModel.LEARNING_WINDOW_DAYS, PricingModel.Setting.value(new BigDecimal("90"))), USER);
        when(settings.findById(TENANT)).thenReturn(Optional.of(existing));
        LocalDate since = LocalDate.of(2026, 9, 1).minusDays(90);
        when(deals.acceptance(isNull(), isNull(), eq(since), anyInt())).thenReturn(List.of());
        when(deals.strategyPicks(since)).thenReturn(Map.of());

        LearningView view = service.learning(TENANT);

        assertThat(view.enabled()).isFalse();
        assertThat(view.strategyEnabled()).isFalse(); // child of learning
        assertThat(view.windowDays()).isEqualTo(90);
        assertThat(view.decisions()).isZero();
        assertThat(view.followRatePct()).isNull();
        assertThat(view.lean().available()).isFalse();
        assertThat(view.habit()).isNull();
        assertThat(view.recent()).isEmpty();
        verify(deals, never()).acceptance(anyString(), any(), any(), anyInt());
    }
}

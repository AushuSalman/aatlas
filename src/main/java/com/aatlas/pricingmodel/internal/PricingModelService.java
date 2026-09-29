package com.aatlas.pricingmodel.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a tenant's pricing-model overrides.
 *
 * <p>Reads are cached under {@link CacheNames#PRICING_MODEL} for the settings screen;
 * writes evict, and the cache manager is transaction-aware, so the eviction lands after
 * the commit rather than before a rollback. The engines do not read this cache: they go
 * through {@code history.Reference.pricingModel()}, a primary-key read per request.
 *
 * <p>Who may write is a policy question, answered by {@link PolicyReader}: the seats whose
 * persona says {@code guardrails: true} - heads of either side, finance and the director -
 * because switching a step of the model off is the same kind of decision as moving the
 * margin floor. The check is here rather than in {@code @PreAuthorize} because the answer
 * is a row in {@code role_policy}, which a tenant may have overridden.
 *
 * <p>What is stored is {@link PricingModel.Config#overrides()}: a setting equal to its
 * default is not written, so the row says only what the tenant changed. Validation runs on
 * the raw request first, so a number outside its range is a 400 rather than silently
 * clamped.
 */
@Service
class PricingModelService {

    /** Fewest picks of one bulk strategy, and its share of all picks, before it counts as a habit. */
    static final int HABIT_MIN_PICKS = 3;
    static final double HABIT_MIN_SHARE_PCT = 60;

    /** The learning view reads at most this many deals; the lean itself decays with age anyway. */
    static final int LEARNING_ROWS = 500;
    static final int RECENT_ROWS = 5;

    private static final Logger log = LoggerFactory.getLogger(PricingModelService.class);
    private static final int MAX_PAGE = 100;

    private final PricingModelRepository settings;
    private final PricingModelHistoryRepository history;
    private final PolicyReader policy;
    private final DealSummaries deals;
    private final AatlasClock clock;

    PricingModelService(
            PricingModelRepository settings,
            PricingModelHistoryRepository history,
            PolicyReader policy,
            DealSummaries deals,
            AatlasClock clock) {
        this.settings = settings;
        this.history = history;
        this.policy = policy;
        this.deals = deals;
        this.clock = clock;
    }

    @Cacheable(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId")
    @Transactional(readOnly = true)
    PricingModelView current(UUID tenantId) {
        return settings.findById(tenantId)
                .map(PricingModelView::of)
                .orElseGet(PricingModelView::defaults);
    }

    @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId")
    @Transactional
    PricingModelView save(UUID tenantId, TenantContext.Actor actor, Map<String, PricingModel.Setting> requested) {
        requireMaySetModel(tenantId, actor);
        validate(requested);
        Map<String, PricingModel.Setting> overrides = PricingModel.Config.of(requested).overrides();
        return write(tenantId, actor, overrides, "set");
    }

    @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId")
    @Transactional
    PricingModelView reset(UUID tenantId, TenantContext.Actor actor) {
        requireMaySetModel(tenantId, actor);
        return write(tenantId, actor, Map.of(), "reset");
    }

    @Transactional(readOnly = true)
    CursorPage<PricingModelHistoryView> history(UUID tenantId, Integer limit, String cursor) {
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_PAGE));
        Limit fetch = Limit.of(size + 1);
        List<PricingModelHistoryEntity> rows = cursor == null || cursor.isBlank()
                ? history.findByTenantIdOrderByIdDesc(tenantId, fetch)
                : history.findByTenantIdAndIdLessThanOrderByIdDesc(tenantId, parseCursor(cursor), fetch);
        return CursorPage.of(rows.stream().map(PricingModelHistoryView::of).toList(), size,
                row -> row.id().toString());
    }

    /**
     * What the tenant's decisions have taught the model, tenant-wide. The same maths the
     * chain runs per item, on every sell deal in the model's window, so the screen shows
     * the lean a brand-new item would inherit and the habit the headline tier follows.
     */
    @Transactional(readOnly = true)
    LearningView learning(UUID tenantId) {
        PricingModel.Config config = PricingModel.Config.of(current(tenantId).overrides());
        LocalDate today = clock.today();
        int windowDays = config.value(PricingModel.LEARNING_WINDOW_DAYS).intValue();
        LocalDate since = today.minusDays(windowDays);

        List<DealSummaries.Acceptance> rows = deals.acceptance(null, null, since, LEARNING_ROWS);
        List<DecisionPatterns.Acceptance> patterns = rows.stream()
                .map(r -> new DecisionPatterns.Acceptance(r.date(), r.suggested(), r.actual(),
                        DecisionPatterns.Acceptance.TENANT))
                .toList();
        DecisionPatterns.Learning lean = DecisionPatterns.learn(patterns, today, config);

        long followed = rows.stream().filter(DealSummaries.Acceptance::followed).count();
        Double followRatePct = rows.isEmpty() ? null : Math.round(followed * 1000.0 / rows.size()) / 10.0;

        Map<String, Long> picks = deals.strategyPicks(since);
        DecisionPatterns.Habit habit = DecisionPatterns.habit(picks, HABIT_MIN_PICKS, HABIT_MIN_SHARE_PCT).orElse(null);

        return new LearningView(
                config.on(PricingModel.LEARNING),
                config.on(PricingModel.LEARNING_STRATEGY),
                windowDays,
                since,
                rows.size(),
                followRatePct,
                lean,
                picks,
                habit,
                rows.stream().limit(RECENT_ROWS).toList());
    }

    // ---- validation ------------------------------------------------------------------

    /**
     * Refuses a request the registry cannot make sense of, with a 400 the form can show
     * key by key: an unknown key, the wrong field for the parameter's type, or a number
     * outside its range. Runs before {@link PricingModel.Config#of} so nothing is
     * silently dropped or clamped on the way in.
     */
    static void validate(Map<String, PricingModel.Setting> requested) {
        if (requested == null) {
            throw ApiException.badRequest("validation_failed", "Send the settings map.");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, PricingModel.Setting> e : requested.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().strip();
            PricingModel.Parameter p = PricingModel.parameter(key).orElse(null);
            if (p == null) {
                fields.put(e.getKey() == null ? "" : e.getKey(), "Not a pricing-model parameter.");
                continue;
            }
            PricingModel.Setting s = e.getValue();
            if (s == null) {
                continue; // "the default"
            }
            if (p.toggle()) {
                if (s.value() != null) {
                    fields.put(key, p.label() + " is a toggle; send on, not value.");
                }
            } else if (s.on() != null) {
                fields.put(key, p.label() + " is a number; send value, not on.");
            } else if (s.value() != null && (s.value().compareTo(p.min()) < 0 || s.value().compareTo(p.max()) > 0)) {
                fields.put(key, "Must be between " + plain(p.min()) + " and " + plain(p.max())
                        + (p.unit() == null || p.unit().isBlank() ? "" : " " + p.unit()) + ".");
            }
        }
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "One or more pricing-model settings are not valid.", Map.of("fields", fields));
        }
    }

    private static String plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    // ---- write -------------------------------------------------------------------------

    private PricingModelView write(UUID tenantId, TenantContext.Actor actor, Map<String, PricingModel.Setting> overrides,
            String action) {
        Instant now = clock.now();
        PricingModelEntity row = settings.findById(tenantId)
                .map(existing -> {
                    existing.apply(overrides, actor.userId());
                    return existing;
                })
                .orElseGet(() -> new PricingModelEntity(tenantId, overrides, actor.userId()));
        row = settings.saveAndFlush(row);

        history.save(new PricingModelHistoryEntity(tenantId, action, overrides, actor.userId(), actor.role(), now));

        log.info("Pricing model {} for tenant {} by user {} ({}): {} override{}", action, tenantId, actor.userId(),
                actor.role(), overrides.size(), overrides.size() == 1 ? "" : "s");
        return PricingModelView.of(row);
    }

    private void requireMaySetModel(UUID tenantId, TenantContext.Actor actor) {
        Persona persona = policy.personaFor(tenantId, actor.role());
        if (!persona.guardrails()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "Your seat cannot change the pricing model. Heads of sales and purchasing, "
                            + "finance and the commercial director can.");
        }
    }

    private static UUID parseCursor(String cursor) {
        try {
            return UUID.fromString(cursor.strip());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_cursor", "That cursor is not one this list issued.");
        }
    }
}

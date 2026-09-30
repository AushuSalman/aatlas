package com.aatlas.pricingmodel.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import com.aatlas.history.PricingModel.Side;
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
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a tenant's pricing-model overrides, one side at a time.
 *
 * <p>The sell model and the buying model are two sides of one registry and share one
 * stored row ({@code pricing_model_settings}), but every endpoint here serves one side:
 * the view is that side's keys, a save replaces that side's overrides and keeps the
 * other's, and a reset clears that side alone. Which side is a query parameter,
 * {@code sell} unless said otherwise, so a caller from before the buying model existed
 * sees what it always did.
 *
 * <p>Reads are cached under {@link CacheNames#PRICING_MODEL}, keyed by tenant and side;
 * a write evicts both sides' entries, because one row backs them both. The cache manager
 * is transaction-aware, so the eviction lands after the commit rather than before a
 * rollback. The engines do not read this cache: they go through
 * {@code history.Reference.pricingModel()}, a primary-key read per request.
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
 * clamped, and a key that belongs to the other side is refused rather than silently
 * dropped by the merge.
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

    /**
     * The side a request names: {@code sell} when absent or blank, else {@code sell} or
     * {@code buy} in any case. Anything else is a 400 the form can show against the
     * {@code side} field, the same shape as a bad setting.
     */
    static Side sideOf(String raw) {
        try {
            return Side.of(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "That is not a side of the pricing model.",
                    Map.of("fields", Map.of("side", "Send sell or buy.")));
        }
    }

    @Cacheable(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId + ':' + #side.name()")
    @Transactional(readOnly = true)
    PricingModelView current(UUID tenantId, Side side) {
        return settings.findById(tenantId)
                .map(row -> PricingModelView.of(side, row))
                .orElseGet(() -> PricingModelView.defaults(side));
    }

    /**
     * Replaces one side's overrides with {@code requested} (normalised: defaults dropped)
     * and keeps the other side's as stored. The screen posts its whole side, so what is
     * sent is what that side becomes.
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId + ':SELL'"),
        @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId + ':BUY'")
    })
    @Transactional
    PricingModelView save(UUID tenantId, TenantContext.Actor actor, Side side,
            Map<String, PricingModel.Setting> requested) {
        requireMaySetModel(tenantId, actor);
        validate(side, requested);
        return write(tenantId, actor, side, requested, "set");
    }

    /** Clears one side's overrides; the other side is untouched. A tenant with no row gets one, on the defaults. */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId + ':SELL'"),
        @CacheEvict(cacheNames = CacheNames.PRICING_MODEL, key = "#tenantId + ':BUY'")
    })
    @Transactional
    PricingModelView reset(UUID tenantId, TenantContext.Actor actor, Side side) {
        requireMaySetModel(tenantId, actor);
        return write(tenantId, actor, side, Map.of(), "reset");
    }

    @Transactional(readOnly = true)
    CursorPage<PricingModelHistoryView> history(UUID tenantId, Side side, Integer limit, String cursor) {
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_PAGE));
        Limit fetch = Limit.of(size + 1);
        List<PricingModelHistoryEntity> rows = cursor == null || cursor.isBlank()
                ? history.findByTenantIdOrderByIdDesc(tenantId, fetch)
                : history.findByTenantIdAndIdLessThanOrderByIdDesc(tenantId, parseCursor(cursor), fetch);
        return CursorPage.of(rows.stream().map(row -> PricingModelHistoryView.of(side, row)).toList(), size,
                row -> row.id().toString());
    }

    /**
     * What the tenant's decisions have taught one side of the model, tenant-wide. The same
     * maths the chain runs per item, on every deal of that side in that side's window, so
     * the screen shows the lean a brand-new item would inherit and the habit the headline
     * tier follows. The buy side learns from costs agreed against a target, the sell side
     * from prices applied against a suggestion; each reads its own knobs.
     */
    @Transactional(readOnly = true)
    LearningView learning(UUID tenantId, Side side) {
        PricingModel.Config config = stored(tenantId);
        boolean buy = side == Side.BUY;
        String dealSide = buy ? DealSummaries.BUY : DealSummaries.SELL;
        String learningKey = buy ? PricingModel.BUY_LEARNING : PricingModel.LEARNING;
        String strategyKey = buy ? PricingModel.BUY_LEARNING_STRATEGY : PricingModel.LEARNING_STRATEGY;
        String windowKey = buy ? PricingModel.BUY_LEARNING_WINDOW_DAYS : PricingModel.LEARNING_WINDOW_DAYS;

        LocalDate today = clock.today();
        int windowDays = config.value(windowKey).intValue();
        LocalDate since = today.minusDays(windowDays);

        List<DealSummaries.Acceptance> rows = deals.acceptance(dealSide, null, null, since, LEARNING_ROWS);
        List<DecisionPatterns.Acceptance> patterns = rows.stream()
                .map(r -> new DecisionPatterns.Acceptance(r.date(), r.suggested(), r.actual(),
                        DecisionPatterns.Acceptance.TENANT))
                .toList();
        DecisionPatterns.Learning lean = DecisionPatterns.learn(patterns, today, config, side);

        long followed = rows.stream().filter(DealSummaries.Acceptance::followed).count();
        Double followRatePct = rows.isEmpty() ? null : Math.round(followed * 1000.0 / rows.size()) / 10.0;

        Map<String, Long> picks = deals.strategyPicks(dealSide, since);
        DecisionPatterns.Habit habit = DecisionPatterns.habit(picks, HABIT_MIN_PICKS, HABIT_MIN_SHARE_PCT).orElse(null);

        return new LearningView(
                side.key(),
                config.on(learningKey),
                config.on(strategyKey),
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
     * key by key: an unknown key, a key of the other side, the wrong field for the
     * parameter's type, or a number outside its range. Runs before the merge so nothing
     * is silently dropped or clamped on the way in.
     */
    static void validate(Side side, Map<String, PricingModel.Setting> requested) {
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
            if (p.side() != side) {
                fields.put(key, p.label() + " belongs to the " + p.side().key() + " model; save it there.");
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

    /**
     * Merges {@code sideOverrides} into the stored map as the new state of {@code side},
     * stores the whole map, and records it in the history as stored - both sides - since
     * the row is what changed.
     */
    private PricingModelView write(UUID tenantId, TenantContext.Actor actor, Side side,
            Map<String, PricingModel.Setting> sideOverrides, String action) {
        Instant now = clock.now();
        PricingModelEntity row = settings.findById(tenantId).orElse(null);
        PricingModel.Config stored = row == null ? PricingModel.Config.defaults() : PricingModel.Config.of(row.getSettings());
        PricingModel.Config merged = stored.withSide(side, sideOverrides);
        Map<String, PricingModel.Setting> overrides = merged.overrides();

        if (row == null) {
            row = new PricingModelEntity(tenantId, overrides, actor.userId());
        } else {
            row.apply(overrides, actor.userId());
        }
        row = settings.saveAndFlush(row);

        history.save(new PricingModelHistoryEntity(tenantId, action, overrides, actor.userId(), actor.role(), now));

        int mine = merged.overrides(side).size();
        log.info("Pricing model {} on the {} side for tenant {} by user {} ({}): {} override{} on that side, {} stored in all",
                action, side.key(), tenantId, actor.userId(), actor.role(), mine, mine == 1 ? "" : "s", overrides.size());
        return PricingModelView.of(side, row);
    }

    /** The whole stored model, both sides, normalised; the defaults for a tenant that never saved. */
    private PricingModel.Config stored(UUID tenantId) {
        return settings.findById(tenantId)
                .map(row -> PricingModel.Config.of(row.getSettings()))
                .orElseGet(PricingModel.Config::defaults);
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

package com.aatlas.policy.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.policy.GuardrailsChanged;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.time.Instant;
import java.util.List;
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
 * Reads and writes a tenant's guardrails.
 *
 * <p>Reads are cached under {@link CacheNames#GUARDRAILS} because every sell
 * recommendation clamps by these numbers; writes evict, and the cache manager is
 * transaction-aware, so the eviction lands after the commit rather than before a rollback.
 *
 * <p>Who may write is a policy question, answered by {@link PolicyReader}: the seats whose
 * persona says {@code guardrails: true} - heads of either side, finance and the director.
 * The check is here rather than in {@code @PreAuthorize} because the answer is a row in
 * {@code role_policy}, which a tenant may have overridden.
 */
@Service
class GuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsService.class);
    private static final int MAX_PAGE = 100;

    private final GuardrailsRepository guardrails;
    private final GuardrailHistoryRepository history;
    private final GuardrailDefaults defaults;
    private final PolicyReader policy;
    private final DomainEventPublisher events;
    private final AatlasClock clock;

    GuardrailsService(
            GuardrailsRepository guardrails,
            GuardrailHistoryRepository history,
            GuardrailDefaults defaults,
            PolicyReader policy,
            DomainEventPublisher events,
            AatlasClock clock) {
        this.guardrails = guardrails;
        this.history = history;
        this.defaults = defaults;
        this.policy = policy;
        this.events = events;
        this.clock = clock;
    }

    @Cacheable(cacheNames = CacheNames.GUARDRAILS, key = "#tenantId")
    @Transactional(readOnly = true)
    GuardrailsView current(UUID tenantId) {
        return guardrails.findById(tenantId)
                .map(GuardrailsView::of)
                .orElseGet(() -> GuardrailsView.defaults(defaults.values()));
    }

    @CacheEvict(cacheNames = CacheNames.GUARDRAILS, key = "#tenantId")
    @Transactional
    GuardrailsView save(UUID tenantId, TenantContext.Actor actor, GuardrailValues requested) {
        requireMaySetGuardrails(tenantId, actor);
        GuardrailValues values = requested.normalised();
        GuardrailLimits.check(values);
        return write(tenantId, actor, values, "set");
    }

    @CacheEvict(cacheNames = CacheNames.GUARDRAILS, key = "#tenantId")
    @Transactional
    GuardrailsView reset(UUID tenantId, TenantContext.Actor actor) {
        requireMaySetGuardrails(tenantId, actor);
        return write(tenantId, actor, defaults.values(), "reset");
    }

    @Transactional(readOnly = true)
    CursorPage<GuardrailHistoryView> history(UUID tenantId, Integer limit, String cursor) {
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, MAX_PAGE));
        Limit fetch = Limit.of(size + 1);
        List<GuardrailHistoryEntity> rows = cursor == null || cursor.isBlank()
                ? history.findByTenantIdOrderByIdDesc(tenantId, fetch)
                : history.findByTenantIdAndIdLessThanOrderByIdDesc(tenantId, parseCursor(cursor), fetch);
        return CursorPage.of(rows.stream().map(GuardrailHistoryView::of).toList(), size,
                row -> row.id().toString());
    }

    private GuardrailsView write(UUID tenantId, TenantContext.Actor actor, GuardrailValues values, String action) {
        Instant now = clock.now();
        GuardrailsEntity row = guardrails.findById(tenantId)
                .map(existing -> {
                    existing.apply(values, actor.userId());
                    return existing;
                })
                .orElseGet(() -> new GuardrailsEntity(tenantId, values, actor.userId()));
        row = guardrails.saveAndFlush(row);

        history.save(new GuardrailHistoryEntity(tenantId, action, values, actor.userId(), actor.role(), now));
        events.publish(new GuardrailsChanged(tenantId, actor.userId(), values.minMarginPct(),
                values.maxDiscountPct(), values.maxSpeedPremiumPct(), values.maxMarketDeviationPct(), now));

        log.info("Guardrails {} for tenant {} by user {} ({})", action, tenantId, actor.userId(), actor.role());
        return GuardrailsView.of(row);
    }

    private void requireMaySetGuardrails(UUID tenantId, TenantContext.Actor actor) {
        Persona persona = policy.personaFor(tenantId, actor.role());
        if (!persona.guardrails()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "Your seat cannot change the pricing guardrails. Heads of sales and purchasing, "
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

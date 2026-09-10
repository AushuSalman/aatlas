package com.aatlas.sell.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.sell.DecisionRecorder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trivial in-memory stand-in for the {@code decisions} module. See {@link DecisionRecorder}
 * for what this stands in for and why. Kept per tenant, newest first, for the life of the
 * process only - a restart loses it, which is the honest cost of a stand-in and is called
 * out in the report rather than papered over with a table this track does not own.
 */
@Component
class DecisionRecorderImpl implements DecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(DecisionRecorderImpl.class);

    private final Map<UUID, List<Recorded>> byTenant = new ConcurrentHashMap<>();
    private final AatlasClock clock;

    DecisionRecorderImpl(AatlasClock clock) {
        this.clock = clock;
    }

    @Override
    public Recorded record(RecordRequest r) {
        // Both write paths on this branch (apply, quote) always close at the price they
        // recommend, so this mirrors the frontend's recordSale/recordDecision pair exactly:
        // a sale at or above the suggestion is "followed".
        boolean followed = r.applied().compareTo(r.recommended()) >= 0;
        BigDecimal gain = followed
                ? r.applied().subtract(r.baselinePrice()).multiply(BigDecimal.valueOf(r.qty()))
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal lost = followed ? BigDecimal.ZERO.setScale(2)
                : r.recommended().subtract(r.applied()).multiply(BigDecimal.valueOf(r.qty()))
                        .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal value = followed ? gain : lost.negate();
        String outcome = followed ? (value.compareTo(new BigDecimal("0.5")) > 0 ? "positive" : "neutral")
                : (value.compareTo(new BigDecimal("-0.5")) < 0 ? "negative" : "neutral");
        String outcomeLabel = outcome.equals("positive") ? "Positive"
                : outcome.equals("negative") ? "Missed" : followed ? "Followed" : "Neutral";

        String id = "dec-" + clock.now().toEpochMilli() + "-" + ThreadLocalRandom.current().nextInt(1000);
        Recorded recorded = new Recorded(id, clock.now(), r.kind(), r.title(), r.itemNumber(), r.storeCode(),
                r.scope(), r.recommended(), r.applied(), followed, gain, lost, value, outcome, outcomeLabel,
                r.expectedImpact(), r.impactLabel(), r.detail(), r.qty(), r.customerName());

        UUID tenantId = TenantContext.requireTenantId();
        byTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(0, recorded);
        log.info("Recorded {} decision {} for {}@{} ({}): {} -> {}", r.kind(), id, r.itemNumber(), r.storeCode(),
                tenantId, r.recommended(), r.applied());
        return recorded;
    }

    @Override
    public List<Recorded> outcomesFor(String itemNumber, String storeCode) {
        UUID tenantId = TenantContext.requireTenantId();
        return byTenant.getOrDefault(tenantId, List.of()).stream()
                .filter(d -> d.itemNumber().equals(itemNumber) && d.storeCode().equals(storeCode))
                .toList();
    }
}

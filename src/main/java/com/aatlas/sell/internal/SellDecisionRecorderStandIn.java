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
 * In-memory index for {@code GET /sell/outcomes} (kept per tenant, newest first, for the life
 * of the process only - a restart loses it, the honest cost of that one query), and - since
 * the merge - also the writer that mirrors every apply/quote into the real {@code decisions}
 * ledger, so History/Overview/Analytics see it the same way they see every other applied
 * recommendation. See {@link DecisionRecorder} for the original stand-in rationale.
 */
@Component
class SellDecisionRecorderStandIn implements DecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SellDecisionRecorderStandIn.class);

    private final Map<UUID, List<Recorded>> byTenant = new ConcurrentHashMap<>();
    private final AatlasClock clock;
    private final com.aatlas.decisions.DecisionRecorder ledger;

    SellDecisionRecorderStandIn(AatlasClock clock, com.aatlas.decisions.DecisionRecorder ledger) {
        this.clock = clock;
        this.ledger = ledger;
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
        mirrorToLedger(r);
        return recorded;
    }

    /**
     * Writes the same apply/quote into {@code com.aatlas.decisions}: one {@link
     * com.aatlas.decisions.Decision} plus one linked sell {@link com.aatlas.decisions.DealRecord}.
     * {@code r.scope()} already carries the store label (every caller in {@code SellService}
     * passes {@code intel.storeLabel()} there), which is exactly what {@code RecordSaleRequest}
     * wants as {@code storeName}. Never lets a ledger-write problem fail the sell response
     * itself - the in-memory record above is this endpoint's real contract with the frontend.
     */
    private void mirrorToLedger(RecordRequest r) {
        try {
            com.aatlas.decisions.Decision decision = ledger.record(new com.aatlas.decisions.RecordDecisionRequest(
                    com.aatlas.decisions.DecisionKind.SELL, r.title(), r.itemNumber(), r.scope(),
                    r.recommended(), r.applied(), r.expectedImpact(), r.impactLabel(), r.detail(), r.qty(), null));
            ledger.recordSale(new com.aatlas.decisions.RecordSaleRequest(
                    r.itemNumber(), r.title(), r.scope(), r.customerName(), r.qty(), r.cost(), r.baselinePrice(),
                    r.recommended(), r.applied(), null, null, decision.id()));
        } catch (RuntimeException ex) {
            log.warn("Could not mirror sell decision for {}@{} into the decisions ledger: {}",
                    r.itemNumber(), r.storeCode(), ex.toString());
        }
    }

    @Override
    public List<Recorded> outcomesFor(String itemNumber, String storeCode) {
        UUID tenantId = TenantContext.requireTenantId();
        return byTenant.getOrDefault(tenantId, List.of()).stream()
                .filter(d -> d.itemNumber().equals(itemNumber) && d.storeCode().equals(storeCode))
                .toList();
    }
}

package com.aatlas.bulk.internal;

import com.aatlas.bulk.DecisionRecorder;
import com.aatlas.bulk.internal.BulkBuyDtos.AwardView;
import com.aatlas.bulk.internal.BulkBuyDtos.LineView;
import com.aatlas.bulk.internal.BulkBuyDtos.PlanView;
import com.aatlas.bulk.internal.BulkBuyDtos.ProjectionView;
import com.aatlas.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes a bulk buy plan, and applies one of its award strategies. */
@Service
public class BulkBuyService {

    private static final Logger log = LoggerFactory.getLogger(BulkBuyService.class);

    private final BulkBuyEngine engine;
    private final DecisionRecorder decisions;
    private final BulkAuthorization authorization;
    private final com.aatlas.decisions.DecisionRecorder ledger;

    BulkBuyService(BulkBuyEngine engine, DecisionRecorder decisions, BulkAuthorization authorization,
            com.aatlas.decisions.DecisionRecorder ledger) {
        this.engine = engine;
        this.decisions = decisions;
        this.authorization = authorization;
        this.ledger = ledger;
    }

    public PlanView plan(String regionKey, List<String> items, double horizon) {
        return engine.plan(regionKey, items, horizon);
    }

    @Transactional
    public ApplyBuyResponse apply(ApplyBuyStrategyRequest request) {
        authorization.requireBulkSeat();
        PlanView plan = engine.plan(request.regionKey(), request.items(), request.horizon());
        ProjectionView chosen = plan.strategies().stream()
                .filter(s -> s.key().equals(request.strategyKey()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("unknown_strategy",
                        "No strategy '" + request.strategyKey() + "' in this plan."));

        Map<String, Integer> qtyByItem = new LinkedHashMap<>();
        for (LineView line : plan.lines()) {
            qtyByItem.put(line.itemNumber(), line.qty());
        }

        List<DecisionRecorder.DealLine> lines = new ArrayList<>();
        for (AwardView award : chosen.awards()) {
            int lineQty = qtyByItem.getOrDefault(award.itemNumber(), 0);
            int qty = (int) Math.round(lineQty * (award.sharePct() / 100.0));
            lines.add(new DecisionRecorder.DealLine(award.itemNumber(), qty, null, award.unitCost(),
                    award.supplierId(), award.supplierName()));
        }

        UUID decisionId = decisions.recordBuyDecision(request.regionKey(), chosen.key(), chosen.totalCost(),
                chosen.savings(), chosen, lines);
        mirrorToLedger(plan, chosen);
        return new ApplyBuyResponse(decisionId, chosen.key(), plan);
    }

    /**
     * Writes the same bulk award onto the real {@code decisions} ledger: one {@link
     * com.aatlas.decisions.Decision} for the strategy, plus one linked buy {@link
     * com.aatlas.decisions.DealRecord} per award. {@code targetCost} equals the awarded
     * price itself - a bulk award is chosen to already be the optimum, so it always reads as
     * followed, unlike a single-item buy where the target is a separate negotiation goal.
     * There is no single destination store at this granularity (the plan spans a region), so
     * {@code destinationId} is omitted - the deal is recorded, but no procurement-ledger badge
     * is attempted, matching {@code RecordPurchaseRequest}'s "omit to record the deal only".
     * Never fails the apply itself if the ledger write has a problem.
     */
    private void mirrorToLedger(PlanView plan, ProjectionView chosen) {
        try {
            Map<String, LineView> byItem = new LinkedHashMap<>();
            for (LineView line : plan.lines()) {
                byItem.put(line.itemNumber(), line);
            }

            com.aatlas.decisions.Decision decision = ledger.record(new com.aatlas.decisions.RecordDecisionRequest(
                    com.aatlas.decisions.DecisionKind.BULK_BUY, chosen.title() + " for " + plan.regionLabel(), null,
                    plan.regionLabel(), null, null, java.math.BigDecimal.valueOf(Math.round(chosen.savings())),
                    "impact", chosen.blurb(), chosen.awards().size(), null));

            for (AwardView award : chosen.awards()) {
                LineView line = byItem.get(award.itemNumber());
                if (line == null) {
                    continue;
                }
                int lineQty = qty(plan, award);
                double baseline = line.incumbent() != null ? line.incumbent().effective() : award.unitCost();
                ledger.recordPurchase(new com.aatlas.decisions.RecordPurchaseRequest(
                        award.itemNumber(), line.name(), award.supplierName(), Math.max(1, lineQty),
                        java.math.BigDecimal.valueOf(baseline), java.math.BigDecimal.valueOf(award.unitCost()),
                        java.math.BigDecimal.valueOf(award.unitCost()), null, true, null, decision.id(),
                        award.supplierId(), null, null));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not mirror bulk buy decision for {} ({}) into the decisions ledger: {}",
                    plan.regionKey(), chosen.key(), ex.toString());
        }
    }

    private static int qty(PlanView plan, AwardView award) {
        for (LineView line : plan.lines()) {
            if (line.itemNumber().equals(award.itemNumber())) {
                return (int) Math.round(line.qty() * (award.sharePct() / 100.0));
            }
        }
        return 0;
    }
}

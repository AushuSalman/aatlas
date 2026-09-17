package com.aatlas.bulk.internal;

import com.aatlas.bulk.DecisionRecorder;
import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import com.aatlas.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes a bulk sell plan, and applies one of its strategies. */
@Service
public class BulkSellService {

    private static final Logger log = LoggerFactory.getLogger(BulkSellService.class);

    private final BulkSellEngine engine;
    private final DecisionRecorder decisions;
    private final BulkAuthorization authorization;
    private final com.aatlas.decisions.DecisionRecorder ledger;

    BulkSellService(BulkSellEngine engine, DecisionRecorder decisions, BulkAuthorization authorization,
            com.aatlas.decisions.DecisionRecorder ledger) {
        this.engine = engine;
        this.decisions = decisions;
        this.authorization = authorization;
        this.ledger = ledger;
    }

    public PlanView plan(String storeId, List<String> items) {
        return engine.plan(storeId, items);
    }

    @Transactional
    public ApplySellResponse apply(ApplySellStrategyRequest request) {
        authorization.requireBulkSeat();
        PlanView plan = engine.plan(request.storeId(), request.items());
        ProjectionView chosen = plan.strategies().stream()
                .filter(s -> s.key().equals(request.strategyKey()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("unknown_strategy",
                        "No strategy '" + request.strategyKey() + "' in this plan."));

        List<DecisionRecorder.DealLine> lines = new ArrayList<>();
        for (LineView line : plan.lines()) {
            Double price = chosen.prices().get(line.itemNumber());
            lines.add(new DecisionRecorder.DealLine(line.itemNumber(), (int) Math.round(line.inventoryUnits()),
                    price, null, null, null));
        }

        double impact = round2(chosen.profit() - plan.current().profit());
        UUID decisionId = decisions.recordSellDecision(request.storeId(), chosen.key(), chosen.revenue(), impact,
                chosen, lines);
        mirrorToLedger(plan, chosen, impact);
        return new ApplySellResponse(decisionId, chosen.key(), plan);
    }

    /**
     * Writes the same bulk apply onto the real {@code decisions} ledger: one {@link
     * com.aatlas.decisions.Decision} for the strategy, plus one linked sell {@link
     * com.aatlas.decisions.DealRecord} per basket line - {@code plan.lines()} already carries
     * each line's cost/current/recommended prices, {@code chosen.prices()} the price this
     * strategy actually applies. Never fails the apply itself if the ledger write has a problem.
     */
    private void mirrorToLedger(PlanView plan, ProjectionView chosen, double impact) {
        try {
            com.aatlas.decisions.Decision decision = ledger.record(new com.aatlas.decisions.RecordDecisionRequest(
                    com.aatlas.decisions.DecisionKind.BULK_SELL, chosen.title() + " at " + plan.storeLabel(), null,
                    plan.storeLabel(), null, null, java.math.BigDecimal.valueOf(Math.round(impact)), "impact",
                    chosen.blurb(), plan.lines().size(), null));

            for (LineView line : plan.lines()) {
                Double price = chosen.prices().get(line.itemNumber());
                if (price == null) {
                    continue;
                }
                ledger.recordSale(new com.aatlas.decisions.RecordSaleRequest(
                        line.itemNumber(), line.name(), plan.storeLabel(), null,
                        Math.max(1, (int) Math.round(line.inventoryUnits())),
                        java.math.BigDecimal.valueOf(line.cost()), java.math.BigDecimal.valueOf(line.current()),
                        java.math.BigDecimal.valueOf(line.recommended()), java.math.BigDecimal.valueOf(price), null,
                        null, decision.id(), plan.storeId()));
            }
        } catch (RuntimeException ex) {
            log.warn("Could not mirror bulk sell decision for {} ({}) into the decisions ledger: {}",
                    plan.storeId(), chosen.key(), ex.toString());
        }
    }

    private static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }
}

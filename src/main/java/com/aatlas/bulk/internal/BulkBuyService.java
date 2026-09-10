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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes a bulk buy plan, and applies one of its award strategies. */
@Service
public class BulkBuyService {

    private final BulkBuyEngine engine;
    private final DecisionRecorder decisions;
    private final BulkAuthorization authorization;

    BulkBuyService(BulkBuyEngine engine, DecisionRecorder decisions, BulkAuthorization authorization) {
        this.engine = engine;
        this.decisions = decisions;
        this.authorization = authorization;
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
        return new ApplyBuyResponse(decisionId, chosen.key(), plan);
    }
}

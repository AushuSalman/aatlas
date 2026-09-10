package com.aatlas.bulk.internal;

import com.aatlas.bulk.DecisionRecorder;
import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import com.aatlas.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes a bulk sell plan, and applies one of its strategies. */
@Service
public class BulkSellService {

    private final BulkSellEngine engine;
    private final DecisionRecorder decisions;
    private final BulkAuthorization authorization;

    BulkSellService(BulkSellEngine engine, DecisionRecorder decisions, BulkAuthorization authorization) {
        this.engine = engine;
        this.decisions = decisions;
        this.authorization = authorization;
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
        return new ApplySellResponse(decisionId, chosen.key(), plan);
    }

    private static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }
}

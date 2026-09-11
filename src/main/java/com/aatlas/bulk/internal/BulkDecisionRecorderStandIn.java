package com.aatlas.bulk.internal;

import com.aatlas.bulk.DecisionRecorder;
import com.aatlas.common.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code TODO(merge): replace with the decisions module's recorder.} See
 * {@code com.aatlas.bulk.DecisionRecorder} for why this module owns
 * {@code bulk_decision}/{@code bulk_deal} in the meantime.
 */
@Service
@Transactional
class BulkDecisionRecorderStandIn implements DecisionRecorder {

    private final BulkDecisionRepository decisions;
    private final BulkDealRepository deals;
    private final ObjectMapper json;

    BulkDecisionRecorderStandIn(BulkDecisionRepository decisions, BulkDealRepository deals, ObjectMapper json) {
        this.decisions = decisions;
        this.deals = deals;
        this.json = json;
    }

    @Override
    public UUID recordSellDecision(String storeCode, String strategyKey, double totalValue, double totalImpact,
            Object payload, List<DealLine> lines) {
        return record("sell", storeCode, strategyKey, totalValue, totalImpact, payload, lines);
    }

    @Override
    public UUID recordBuyDecision(String regionKey, String strategyKey, double totalValue, double totalImpact,
            Object payload, List<DealLine> lines) {
        return record("buy", regionKey, strategyKey, totalValue, totalImpact, payload, lines);
    }

    private UUID record(String kind, String target, String strategyKey, double totalValue, double totalImpact,
            Object payload, List<DealLine> lines) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId().orElse(null);
        Map<String, Object> payloadMap = json.convertValue(payload, new TypeReference<>() {});

        BulkDecisionEntity decision = new BulkDecisionEntity(tenantId, kind, strategyKey, target, lines.size(),
                money(totalValue), money(totalImpact), payloadMap, userId);
        decisions.save(decision);

        for (DealLine line : lines) {
            deals.save(new BulkDealEntity(tenantId, decision.getId(), line.itemNumber(), line.qty(),
                    money(line.unitPrice()), money(line.unitCost()), line.supplierKey(), line.supplierName()));
        }
        return decision.getId();
    }

    private static BigDecimal money(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}

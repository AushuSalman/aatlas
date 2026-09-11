package com.aatlas.buy.internal;

import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.BuySelectResult;
import com.aatlas.buy.BuyWhatIfResult;
import com.aatlas.buy.Negotiation;
import com.aatlas.buy.ProcurementPlan;
import com.aatlas.buy.SupplierEval;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buy screens' seven endpoints: the landed-cost panel, the fuller supplier evaluation,
 * the side-by-side compare, the procurement plan, its what-if scenarios, the negotiation
 * letter, and committing to an option.
 */
@Service
class BuyService {

    private static final Logger log = LoggerFactory.getLogger(BuyService.class);

    private final BuyRecommendationEngine recommendationEngine;
    private final BuyIntelEngine buyIntelEngine;
    private final ProcurementEngine procurementEngine;
    private final PolicyReader policy;
    private final DecisionRecorder decisions;
    private final com.aatlas.decisions.DecisionRecorder ledger;

    BuyService(BuyRecommendationEngine recommendationEngine, BuyIntelEngine buyIntelEngine,
            ProcurementEngine procurementEngine, PolicyReader policy, DecisionRecorder decisions,
            com.aatlas.decisions.DecisionRecorder ledger) {
        this.recommendationEngine = recommendationEngine;
        this.buyIntelEngine = buyIntelEngine;
        this.procurementEngine = procurementEngine;
        this.policy = policy;
        this.decisions = decisions;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    BuyRecommendation recommendation(String item, String destination, String supplier) {
        return recommendationEngine.build(item, destination, supplier);
    }

    @Transactional(readOnly = true)
    BuyIntel intel(String item, String region, int qty, String destination) {
        return buyIntelEngine.getBuyIntel(item, region, qty, destination);
    }

    @Transactional(readOnly = true)
    List<SupplierEval> compare(String item, String region, int qty, String destination) {
        return intel(item, region, qty, destination).suppliers();
    }

    @Transactional(readOnly = true)
    ProcurementPlan plan(String item, String region, int qty, int requiredDays, String priority, String destination) {
        BuyIntel i = intel(item, region, qty, destination);
        return procurementEngine.procurementPlan(i, requiredDays, priority, null);
    }

    @Transactional(readOnly = true)
    BuyWhatIfResult whatIf(String item, String region, int qty, int requiredDays, String priority, String scenario) {
        BuyIntel i = intel(item, region, qty, null);
        ProcurementPlan p = procurementEngine.procurementPlan(i, requiredDays, priority, null);
        return procurementEngine.buyWhatIf(i, p, scenario);
    }

    @Transactional(readOnly = true)
    Negotiation negotiation(String item, String region, int qty, String destination) {
        return intel(item, region, qty, destination).negotiation();
    }

    /**
     * Select a procurement option: writes a decision and a purchase (see {@link
     * DecisionRecorder}), or sends an approval request when the order is over the signed-in
     * seat's limit ({@link Persona#canApprove}).
     */
    @Transactional
    BuySelectResult select(BuySelectRequest request) {
        String role = TenantContext.current().map(TenantContext.Actor::role)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "unauthenticated", "No seat bound to this request."));
        Persona persona = policy.personaFor(TenantContext.requireTenantId(), role);
        boolean canApproveAlone = persona.canApprove(request.orderValue());

        if (canApproveAlone) {
            decisions.record(request.itemNumber(), request.regionKey(), request.destinationId(),
                    request.optionKey(), request.orderValue(), BuyDecisionEntity.STATUS_RECORDED, null, null);
            mirrorToLedger(request);
            return BuySelectResult.approved();
        }
        decisions.record(request.itemNumber(), request.regionKey(), request.destinationId(), request.optionKey(),
                request.orderValue(), BuyDecisionEntity.STATUS_PENDING_APPROVAL, persona.approver(),
                persona.approveLimit());
        return BuySelectResult.pending(persona.approveLimit(), persona.approver());
    }

    /**
     * Writes an immediately-approved award onto the real {@code decisions} ledger too: one
     * {@link com.aatlas.decisions.Decision} plus one linked buy {@link
     * com.aatlas.decisions.DealRecord}, and - because a supplier and a destination are always
     * known here - a real line on the {@code analytics} procurement ledger. A request pending
     * approval is not yet a closed deal, so only the approved branch calls this.
     *
     * <p>{@code qty} defaults to 1 when the caller omits it (older frontend builds); the
     * landed-cost panel for (item, destination, optionKey) supplies the rest: {@code
     * incumbentCost} as the pre-decision baseline, {@code currentCost} as what the chosen
     * supplier actually costs (the agreed price), {@code targetCost} as the negotiation
     * target - never fails the request itself if the ledger write has a problem.
     */
    private void mirrorToLedger(BuySelectRequest request) {
        try {
            BuyRecommendation rec = recommendationEngine.build(
                    request.itemNumber(), request.destinationId(), request.optionKey());
            int qty = request.qty() != null && request.qty() > 0 ? request.qty() : 1;

            com.aatlas.decisions.Decision decision = ledger.record(new com.aatlas.decisions.RecordDecisionRequest(
                    com.aatlas.decisions.DecisionKind.BUY,
                    rec.description() + " awarded to " + rec.supplierName(), request.itemNumber(),
                    request.regionKey(), java.math.BigDecimal.valueOf(rec.targetCost()),
                    java.math.BigDecimal.valueOf(rec.currentCost()), request.orderValue(), "one-time order",
                    "Awarded to " + rec.supplierName() + " at " + rec.destinationName(), qty, null));

            ledger.recordPurchase(new com.aatlas.decisions.RecordPurchaseRequest(
                    request.itemNumber(), rec.description(), rec.supplierName(), qty,
                    java.math.BigDecimal.valueOf(rec.incumbentCost()), java.math.BigDecimal.valueOf(rec.targetCost()),
                    java.math.BigDecimal.valueOf(rec.currentCost()), null, true, request.destinationId(),
                    decision.id(), rec.supplierId(), null, null));
        } catch (RuntimeException ex) {
            log.warn("Could not mirror buy award for {}@{} (supplier {}) into the decisions ledger: {}",
                    request.itemNumber(), request.destinationId(), request.optionKey(), ex.toString());
        }
    }
}

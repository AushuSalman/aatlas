package com.aatlas.buy.internal;

import com.aatlas.approvals.ApprovalRequester;
import com.aatlas.approvals.RaiseApprovalRequest;
import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.BuySelectResult;
import com.aatlas.buy.BuyWhatIfResult;
import com.aatlas.buy.Negotiation;
import com.aatlas.buy.ProcurementPlan;
import com.aatlas.buy.SupplierEval;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.DecisionKind;
import com.aatlas.decisions.DecisionStatus;
import com.aatlas.decisions.RecordDecisionRequest;
import com.aatlas.decisions.RecordPurchaseRequest;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final ApprovalRequester approvals;

    BuyService(BuyRecommendationEngine recommendationEngine, BuyIntelEngine buyIntelEngine,
            ProcurementEngine procurementEngine, PolicyReader policy, DecisionRecorder decisions,
            com.aatlas.decisions.DecisionRecorder ledger, ApprovalRequester approvals) {
        this.recommendationEngine = recommendationEngine;
        this.buyIntelEngine = buyIntelEngine;
        this.procurementEngine = procurementEngine;
        this.policy = policy;
        this.decisions = decisions;
        this.ledger = ledger;
        this.approvals = approvals;
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
     * DecisionRecorder}), or - over the signed-in seat's limit ({@link Persona#canApprove}) -
     * raises a real {@code approvals} request the same way {@code rfq.RfqService#award} does.
     */
    @Transactional
    BuySelectResult select(BuySelectRequest request) {
        String role = TenantContext.current().map(TenantContext.Actor::role)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "unauthenticated", "No seat bound to this request."));
        Persona persona = policy.personaFor(TenantContext.requireTenantId(), role);
        boolean canApproveAlone = persona.canApprove(request.orderValue());

        BuyRecommendation rec = recommendationEngine.build(request.itemNumber(), request.destinationId(),
                request.supplierId() != null && !request.supplierId().isBlank() ? request.supplierId() : null);
        int qty = request.qty() != null && request.qty() > 0 ? request.qty() : 1;
        String supplierId = request.supplierId() != null && !request.supplierId().isBlank()
                ? request.supplierId() : rec.supplierId();
        // What was actually agreed, when the caller knows it - the winning quote, or the
        // price on a purchase being recorded after the fact. The model's landed estimate is
        // only the fallback; recording it as though it were the deal made every saving on
        // the history screen the model grading itself.
        BigDecimal agreed = request.unitCost() != null ? request.unitCost() : rec.currentCost();
        // The first purchase of an item nobody has priced has no target to be measured against:
        // the price agreed is the benchmark. purchase_order.target is NOT NULL, and a null here
        // failed the whole request, so the one way to seed an item's history was the one refused.
        BigDecimal target = rec.targetCost() != null ? rec.targetCost() : agreed;

        RecordDecisionRequest decisionRequest = new RecordDecisionRequest(DecisionKind.BUY,
                rec.description() + " awarded to " + rec.supplierName(), request.itemNumber(),
                request.regionKey(), target, agreed, request.orderValue(), "one-time order",
                "Awarded to " + rec.supplierName() + " at " + rec.destinationName(), qty, null);

        if (canApproveAlone) {
            Decision decision = ledger.record(decisionRequest);
            BuyDecisionEntity entity = decisions.record(request.itemNumber(), request.regionKey(),
                    request.destinationId(), request.optionKey(), supplierId, qty, request.orderValue(),
                    BuyDecisionEntity.STATUS_RECORDED, null, null);
            entity.setDecisionId(decision.id());
            decisions.save(entity);
            mirrorPurchase(rec, qty, decision.id(), agreed, target, request.purchasedOn());
            return BuySelectResult.approved();
        }

        Decision decision = ledger.recordPending(decisionRequest);
        BuyDecisionEntity entity = decisions.record(request.itemNumber(), request.regionKey(),
                request.destinationId(), request.optionKey(), supplierId, qty, request.orderValue(),
                BuyDecisionEntity.STATUS_PENDING_APPROVAL, persona.approver(), persona.approveLimit());
        entity.setDecisionId(decision.id());
        decisions.save(entity);
        approvals.raise(new RaiseApprovalRequest(decision.id(), approverRoleKeyFor(persona,
                TenantContext.requireTenantId()), request.orderValue(),
                "Buy " + request.itemNumber() + ": " + rec.description() + " to " + rec.supplierName()));
        return BuySelectResult.pending(persona.approveLimit(), persona.approver());
    }

    /**
     * See {@code rfq.RfqService#approverRoleKeyFor}: {@link Persona#approver()} is a display
     * title, not the seat key {@code approvals} filters "pending for my role" by - resolved by
     * matching the title against {@code policy}'s own persona list.
     */
    private String approverRoleKeyFor(Persona persona, UUID tenantId) {
        if (persona.approver() == null) {
            return null;
        }
        return policy.personasFor(tenantId).stream()
                .filter(p -> persona.approver().equals(p.title()))
                .map(Persona::key)
                .findFirst()
                .orElse(persona.approver());
    }

    /**
     * Writes the real purchase: one {@link com.aatlas.decisions.DealRecord}, and - because a
     * supplier and a destination are always known here - a real line on the {@code analytics}
     * procurement ledger. Never fails the request itself if the ledger write has a problem.
     */
    private void mirrorPurchase(BuyRecommendation rec, int qty, UUID decisionId, BigDecimal agreed, BigDecimal target,
            LocalDate date) {
        try {
            ledger.recordPurchase(new RecordPurchaseRequest(
                    rec.itemNumber(), rec.description(), rec.supplierName(), qty,
                    rec.incumbentCost() != null ? rec.incumbentCost() : (rec.currentCost() != null ? rec.currentCost() : agreed),
                    target, agreed, date, true, rec.destinationId(),
                    decisionId, rec.supplierId(), null, null));
        } catch (RuntimeException ex) {
            log.warn("Could not mirror buy award for {}@{} (supplier {}) into the decisions ledger: {}",
                    rec.itemNumber(), rec.destinationId(), rec.supplierId(), ex.toString());
        }
    }

    // -- approval outcomes -----------------------------------------------------------------
    //
    // Called by BuyApprovalOutcomeListener with TenantContext already bound to the granting
    // event's tenant (Modulith's worker thread does not inherit it - see rfq's own listener).

    @Transactional
    void onApprovalGranted(UUID decisionId) {
        Optional<BuyDecisionEntity> entityOpt = decisions.findByDecisionId(decisionId);
        if (entityOpt.isEmpty()) {
            return;
        }
        BuyDecisionEntity entity = entityOpt.get();
        if (entity.getSupplierId() == null) {
            return;
        }
        BuyRecommendation rec = recommendationEngine.build(entity.getItemNumber(), entity.getDestinationStoreCode(),
                entity.getSupplierId());
        int qty = entity.getQty() != null && entity.getQty() > 0 ? entity.getQty() : 1;
        // The agreed unit price is not stored on its own, but the order value it produced is,
        // and so is the quantity - so it is recovered exactly rather than replaced by today's
        // model estimate. Only when a quantity was actually sent: an older caller that omitted
        // it sent a total, and dividing that by the default of one would record the total as
        // a unit price.
        BigDecimal agreed = entity.getQty() != null && entity.getQty() > 0
                && entity.getOrderValue() != null && entity.getOrderValue().signum() > 0
                ? entity.getOrderValue().divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP)
                : rec.currentCost();
        // Recorded on the day it was signed off: that is when the commitment became real.
        mirrorPurchase(rec, qty, decisionId, agreed, rec.targetCost() != null ? rec.targetCost() : agreed, null);
        ledger.resolve(decisionId, DecisionStatus.APPLIED);
        entity.setStatus(BuyDecisionEntity.STATUS_RECORDED);
        decisions.save(entity);
    }

    @Transactional
    void onApprovalRejected(UUID decisionId) {
        decisions.findByDecisionId(decisionId).ifPresent(entity -> {
            entity.setStatus("rejected");
            decisions.save(entity);
        });
    }
}

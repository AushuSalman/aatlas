package com.aatlas.bulk.internal;

import com.aatlas.bulk.DecisionRecorder;
import com.aatlas.bulk.internal.BulkBuyDtos.AwardView;
import com.aatlas.bulk.internal.BulkBuyDtos.LineView;
import com.aatlas.bulk.internal.BulkBuyDtos.PlanView;
import com.aatlas.bulk.internal.BulkBuyDtos.ProjectionView;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import com.aatlas.history.Reference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes a bulk buy plan, and applies one of its award strategies.
 *
 * <p>The plan itself is {@link BulkBuyEngine}'s pure arithmetic. On top of it, when the
 * tenant's buying model follows their usual strategy ({@code buy.learning.strategy}), the
 * strategy they mostly pick in the basket becomes the recommended one - the engine stays a
 * pure function of the basket, and the habit is read here from the decision ledger.
 */
@Service
public class BulkBuyService {

    private static final Logger log = LoggerFactory.getLogger(BulkBuyService.class);

    /** Fewest bulk picks of one strategy, and its share of all picks, before it counts as a habit. */
    static final int HABIT_MIN_PICKS = 3;
    static final double HABIT_MIN_SHARE_PCT = 60;

    static final String USUAL_CHOICE = " (your usual choice)";

    private final BulkBuyEngine engine;
    private final DecisionRecorder decisions;
    private final BulkAuthorization authorization;
    private final com.aatlas.decisions.DecisionRecorder ledger;
    private final DealSummaries deals;
    private final Reference reference;
    private final AatlasClock clock;

    BulkBuyService(BulkBuyEngine engine, DecisionRecorder decisions, BulkAuthorization authorization,
            com.aatlas.decisions.DecisionRecorder ledger, DealSummaries deals, Reference reference, AatlasClock clock) {
        this.engine = engine;
        this.decisions = decisions;
        this.authorization = authorization;
        this.ledger = ledger;
        this.deals = deals;
        this.reference = reference;
        this.clock = clock;
    }

    public PlanView plan(String regionKey, List<String> items, double horizon) {
        return withHabit(engine.plan(regionKey, items, horizon));
    }

    @Transactional
    public ApplyBuyResponse apply(ApplyBuyStrategyRequest request) {
        authorization.requireBulkSeat();
        PlanView plan = withHabit(engine.plan(request.regionKey(), request.items(), request.horizon()));
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
     * The plan with the tenant's usual strategy recommended, when the buying model says to
     * follow it and the ledger shows a clear habit ({@link DecisionPatterns#habit}) for a
     * strategy this plan has. A failing read - a tenant whose decision tables are not there
     * yet - leaves the engine's own recommendation, with a warning.
     */
    PlanView withHabit(PlanView plan) {
        try {
            PricingModel.Config cfg = reference.pricingModel();
            if (!cfg.on(PricingModel.BUY_LEARNING_STRATEGY)) {
                return plan;
            }
            LocalDate since = clock.today().minusDays((long) cfg.number(PricingModel.BUY_LEARNING_WINDOW_DAYS));
            Optional<DecisionPatterns.Habit> habit = DecisionPatterns.habit(
                    deals.strategyPicks(DealSummaries.BUY, since), HABIT_MIN_PICKS, HABIT_MIN_SHARE_PCT);
            if (habit.isEmpty()) {
                return plan;
            }
            return recommend(plan, habit.get().strategyKey());
        } catch (RuntimeException ex) {
            log.warn("Decision history unavailable for the bulk buy plan in {} ({}); keeping the engine's recommendation",
                    plan.regionKey(), ex.getMessage());
            return plan;
        }
    }

    /** The plan with {@code strategyKey} recommended and marked as the usual choice; unchanged when the plan lacks it. */
    static PlanView recommend(PlanView plan, String strategyKey) {
        if (plan.strategies().stream().noneMatch(s -> s.key().equals(strategyKey))) {
            return plan;
        }
        List<ProjectionView> strategies = new ArrayList<>();
        for (ProjectionView s : plan.strategies()) {
            strategies.add(s.key().equals(strategyKey) ? withBlurb(s, usualChoice(s.blurb())) : s);
        }
        return new PlanView(plan.regionKey(), plan.regionLabel(), plan.lines(), plan.totalUnits(), plan.currentCost(),
                plan.optimizedCost(), plan.savings(), strategies, strategyKey);
    }

    private static String usualChoice(String blurb) {
        if (blurb == null || blurb.isBlank()) {
            return "Your usual choice.";
        }
        if (blurb.endsWith(USUAL_CHOICE) || blurb.endsWith(USUAL_CHOICE + ".")) {
            return blurb;
        }
        return blurb.endsWith(".") ? blurb.substring(0, blurb.length() - 1) + USUAL_CHOICE + "." : blurb + USUAL_CHOICE;
    }

    private static ProjectionView withBlurb(ProjectionView s, String blurb) {
        return new ProjectionView(s.key(), s.title(), blurb, s.totalCost(), s.savings(), s.savingsPct(), s.risk(),
                s.avgOtifPct(), s.avgLeadDays(), s.fulfilmentPct(), s.supplierCount(), s.dependency(), s.awards(),
                s.benefits());
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

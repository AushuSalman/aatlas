package com.aatlas.pricingmodel.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.DecisionPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What one side of the model has learned from this tenant's own decisions, across the
 * business: the lean it would apply where no closer history exists, the bulk strategy
 * they reach for, and the most recent decisions the lean is derived from.
 *
 * <p>On the sell side a decision is a price applied against a suggestion; on the buy side
 * it is a landed cost agreed against a target, so {@code recent[].suggested} is the target
 * and {@code recent[].actual} the agreed cost, and a decision counts as followed when the
 * cost agreed was at or under the target.
 *
 * @param side {@code sell} or {@code buy}
 * @param enabled whether this side's learning step is on in the tenant's model
 * @param strategyEnabled whether this side's usual-strategy step is on
 * @param windowDays this side's look-back
 * @param since the first day inside the window
 * @param decisions deals on this side with both a suggested and an actual price in the window
 * @param followRatePct how many of those took the suggestion as offered (buy: agreed at or under the target), 0-100;
 *                      absent with none
 * @param lean the tenant-wide lean, or why there is none yet
 * @param strategyPicks bulk strategy key on this side to how often it was applied in the window
 * @param habit the strategy picked most, when it is a clear habit; absent otherwise
 * @param recent the newest decisions in the window, at most five
 */
@Schema(name = "PricingModelLearning", description = "What the tenant's own decisions have taught one side of the model.")
record LearningView(
        @Schema(allowableValues = {"sell", "buy"}) String side,
        boolean enabled,
        boolean strategyEnabled,
        int windowDays,
        LocalDate since,
        int decisions,
        Double followRatePct,
        DecisionPatterns.Learning lean,
        Map<String, Long> strategyPicks,
        DecisionPatterns.Habit habit,
        List<DealSummaries.Acceptance> recent) {
}

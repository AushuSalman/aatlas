package com.aatlas.pricingmodel.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.DecisionPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the model has learned from this tenant's own decisions, across the business: the
 * lean it would apply where no closer history exists, the bulk strategy they reach for,
 * and the most recent decisions the lean is derived from.
 *
 * @param enabled whether the learning step is on in the tenant's model
 * @param strategyEnabled whether the usual-strategy step is on
 * @param windowDays the model's look-back
 * @param since the first day inside the window
 * @param decisions sell deals with both a suggested and an actual price in the window
 * @param followRatePct how many of those took the suggestion as offered, 0-100; absent with none
 * @param lean the tenant-wide lean, or why there is none yet
 * @param strategyPicks bulk sell strategy key to how often it was applied in the window
 * @param habit the strategy picked most, when it is a clear habit; absent otherwise
 * @param recent the newest decisions in the window, at most five
 */
@Schema(name = "PricingModelLearning", description = "What the tenant's own decisions have taught the model.")
record LearningView(
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

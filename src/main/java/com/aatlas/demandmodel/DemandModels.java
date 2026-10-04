package com.aatlas.demandmodel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The tenant's demand model, as the rest of the API reads it: a forecast for one item at one
 * branch at a price, the model's status with its per-pair report, and training on demand.
 *
 * <p>Every answer carries whether it may be used. A pair is {@code usable} only when the model
 * beat the naive baseline on that pair's held-out weeks and its probed price sensitivity has
 * the right sign; a caller that gets {@code usable == false} should fall back to the rule
 * engine's own estimate, which is what the chains do.
 */
public interface DemandModels {

    /**
     * The model's answer for a pair at a price, under the forecasting model the tenant chose; empty when the
     * tenant has no trained model or the pair was not in it.
     */
    Optional<Forecast> forecast(String itemNumber, String storeCode, BigDecimal price);

    /**
     * Choose the forecasting model for the current tenant: a key from {@link Status#forecastModels}. Takes
     * effect at once, with no retraining. Only seats that may change the pricing model.
     */
    Status chooseForecastModel(String key);

    /** A forecasting model a tenant can choose. {@code auto} reads, item by item, the forecaster proven on it. */
    record ModelOption(String key, String label, String hint) {
    }

    /** The current tenant's model: whether and when it trained, on how much, and the per-pair report. */
    Status status();

    /** Train now for the current tenant. Only seats that may change the pricing model. */
    Status train();

    /** Whether the current tenant has a trained model at all: tells "no model yet" from "this pair is not in it". */
    boolean trained();

    /**
     * @param unitsPerWeek the forecast model's units for next week at {@code price}, from where demand is now
     * @param settledUnitsPerWeek the response model's units a week at {@code price} once demand has settled at it:
     *        what a pricing decision is about
     * @param elasticity the price sensitivity the model implies around the pair's last price, as a
     *        log-log slope (−1.5 means a 10% rise costs about 15% of the units); negative is the
     *        expected sign
     * @param usable beat the baseline on the held-out weeks and sensitivity has the right sign
     * @param errorPct the model's error over the held-out weeks, as a percentage of the units sold
     * @param baselineErrorPct the same for the naive baseline (the trailing eight-week mean)
     */
    record Forecast(
            BigDecimal unitsPerWeek,
            BigDecimal settledUnitsPerWeek,
            BigDecimal elasticity,
            boolean usable,
            boolean beatsBaseline,
            int weeks,
            BigDecimal errorPct,
            BigDecimal baselineErrorPct,
            Instant trainedAt,
            String note,
            /** Weekly units the model expects over the next four weeks at {@code price}. */
            BigDecimal horizonUnitsPerWeek,
            /** The pair's actual weekly units over its last eight weeks. */
            BigDecimal trailingUnitsPerWeek,
            /** A forecaster beat the recent average on this pair in two back-tests in a row: the demand step may read it. */
            boolean forecastUsable,
            BigDecimal horizonErrorPct,
            BigDecimal horizonBaselineErrorPct,
            /** The forecaster the four-week figures are from, as a person reads it: "Croston SBA", "Random forest". */
            String forecastMethod,
            /** Why the forecast is or is not in use for the pair, in a sentence. */
            String forecastNote,
            /** That forecaster beat the recent average on this pair in both back-tests; false when it is used only because it was chosen. */
            boolean forecastProven) {
    }

    /** One item at one branch, as the training run scored it. */
    record PairReport(
            String itemNumber,
            String storeCode,
            String category,
            int weeks,
            BigDecimal holdoutUnits,
            BigDecimal errorPct,
            BigDecimal baselineErrorPct,
            boolean beatsBaseline,
            boolean usable,
            BigDecimal elasticity,
            /** What the chain's own log-log regression says for the same pair, for comparison; null when it has none. */
            BigDecimal regressionElasticity,
            String regressionBasis,
            BigDecimal lastPrice,
            BigDecimal forecastNextWeek,
            BigDecimal settledNextWeek,
            String note,
            BigDecimal horizonUnitsPerWeek,
            BigDecimal trailingUnitsPerWeek,
            boolean forecastUsable,
            BigDecimal horizonErrorPct,
            BigDecimal horizonBaselineErrorPct,
            String forecastMethod,
            String forecastNote) {
    }

    /**
     * @param rows weekly training rows the last run used
     * @param pairs item-branch pairs in the model
     * @param pairsUsable pairs where the model beat the baseline and may answer
     * @param from the first training week; {@code to} the last complete week
     */
    record Status(
            boolean trained,
            Instant trainedAt,
            int rows,
            int pairs,
            int pairsUsable,
            /** Pairs where the four-week demand forecast beat the recent average and may be read by the demand step. */
            int pairsForecastUsable,
            int weeks,
            LocalDate from,
            LocalDate to,
            int holdoutWeeks,
            long trainMillis,
            String note,
            /** Sales rows recorded or uploaded after the last training run: the model has not seen them. */
            int salesSinceTrained,
            /** Sales dated in the week still open. A half-finished week is not learned from; see {@code openWeekCountedFrom}. */
            int salesInOpenWeek,
            /** The Monday from which a training run counts the open week's sales. */
            LocalDate openWeekCountedFrom,
            /** Sales in finished weeks after the last one the model trained through: the next run picks them up. */
            int salesAwaitingRetrain,
            List<PairReport> report,
            /** The forecasting model chosen in Settings, as a person reads it: "Automatic", "Chronos", "Random forest". */
            String forecastModel,
            /** The same as its key, one of {@code forecastModels}. */
            String forecastModelKey,
            /** Every forecasting model that can be chosen, in the order it is offered. */
            List<ModelOption> forecastModels) {
    }
}

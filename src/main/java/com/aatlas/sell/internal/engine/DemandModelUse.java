package com.aatlas.sell.internal.engine;

import java.math.BigDecimal;

/**
 * What the tenant's trained demand model did for one item at one branch, so the Sell screen can
 * say so whether or not the model answered. Silence when the model was not used read as "the
 * model is not doing anything"; this says why.
 *
 * <p>The model can help in two places, and each has its own verdict. Its <em>price read</em>
 * ({@code status}) feeds the price-sensitivity step. Its <em>four-week forecast</em>
 * ({@code forecastStatus}) feeds the demand step. Each is used only where it proved itself on
 * weeks the model had not seen.
 *
 * @param status the price read: {@code used}, {@code not_usable} (in the model but not proven),
 *        {@code not_in_model} (too little history for the pair), {@code not_trained} (no model
 *        yet) or {@code off} (switched off in the pricing model)
 * @param note one plain sentence about the price read
 * @param elasticity the model's probed price response for the pair, when it has one
 * @param errorPct the one-week forecast's held-out error for the pair, percent of units sold
 * @param baselineErrorPct the naive baseline's, the same way
 * @param forecastStatus the four-week forecast: {@code used}, {@code not_usable} or {@code off};
 *        null when the pair is not in a model at all
 * @param forecastNote one plain sentence about the forecast
 * @param horizonUnits weekly units the model expects over the next four weeks at today's price
 * @param trailingUnits the pair's actual weekly units over its last eight weeks
 * @param horizonErrorPct the four-week forecast's held-out error; {@code horizonBaselineErrorPct} the recent average's
 */
public record DemandModelUse(String status, String note, BigDecimal elasticity, BigDecimal errorPct,
        BigDecimal baselineErrorPct, String forecastStatus, String forecastNote, BigDecimal horizonUnits,
        BigDecimal trailingUnits, BigDecimal horizonErrorPct, BigDecimal horizonBaselineErrorPct) {

    static final String USED = "used";
    static final String NOT_USABLE = "not_usable";
    static final String NOT_IN_MODEL = "not_in_model";
    static final String NOT_TRAINED = "not_trained";
    static final String OFF = "off";

    /** The model has nothing on this pair: one status and sentence covers both of its uses. */
    static DemandModelUse of(String status, String note) {
        return new DemandModelUse(status, note, null, null, null, null, null, null, null, null, null);
    }

    /** Whether the model moved anything in this recommendation. */
    boolean usedAnywhere() {
        return USED.equals(status) || USED.equals(forecastStatus);
    }
}

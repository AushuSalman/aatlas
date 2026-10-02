package com.aatlas.sell.internal.engine;

import java.math.BigDecimal;

/**
 * What the tenant's trained demand model did for one item at one branch, so the Sell screen can
 * say so whether or not the model answered. Silence when the model was not used read as "the
 * model is not doing anything"; this says why.
 *
 * @param status {@code used} (its price sensitivity entered the chain), {@code not_usable} (the
 *        pair is in the model but did not prove itself), {@code not_in_model} (too little history
 *        for the pair), {@code not_trained} (the business has no trained model yet) or {@code off}
 *        (switched off in the pricing model)
 * @param note one plain sentence for the screen
 * @param elasticity the model's probed price response for the pair, when it has one
 * @param errorPct the model's held-out error for the pair, percent of units sold
 * @param baselineErrorPct the naive baseline's, the same way
 */
public record DemandModelUse(String status, String note, BigDecimal elasticity, BigDecimal errorPct,
        BigDecimal baselineErrorPct) {

    static final String USED = "used";
    static final String NOT_USABLE = "not_usable";
    static final String NOT_IN_MODEL = "not_in_model";
    static final String NOT_TRAINED = "not_trained";
    static final String OFF = "off";

    static DemandModelUse of(String status, String note) {
        return new DemandModelUse(status, note, null, null, null);
    }
}

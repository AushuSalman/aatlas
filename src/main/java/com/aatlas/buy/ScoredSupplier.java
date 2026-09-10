package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One supplier, scored for one order: the route chosen, the odds of making the date, the
 * situational cost (effective cost plus the expected cost of arriving late), and the
 * five-way sub-score behind the headline score. The frontend's {@code ScoredSupplier}.
 *
 * <p>{@code s} keeps the frontend's own (one-letter) field name for the evaluated supplier.
 */
@Schema(name = "ScoredSupplier")
public record ScoredSupplier(
        SupplierEval s,
        SupplierRisk risk,
        Route route,
        List<Route> routes,
        Delivery delivery,
        double situationalCost,
        double lateCostPerUnit,
        double recoveryPerUnit,
        int score,
        SubScores sub,
        double rating,
        String ratingLabel) {

    public record SubScores(double cost, double speed, double reliability, double risk, double relationship) {
    }
}

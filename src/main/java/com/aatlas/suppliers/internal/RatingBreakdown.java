package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The four bars under a rating, 1.0-5.0 each. Mirrors {@code RatingBreakdown} in the
 * frontend's {@code intel/suppliers.ts}.
 *
 * <p>Each dimension is null when its input was never provided or observed: a supplier known
 * only from a purchase order has no defect rate on file, so {@code quality} is absent rather
 * than a guess. {@link SupplierScoring#overall} takes the weighted mean over whichever
 * dimensions are present.
 */
@Schema(name = "RatingBreakdown")
record RatingBreakdown(Double quality, Double delivery, Double communication, Double pricing) {

    /** The order the frontend iterates in; ties in "strongest" and "weakest" resolve by it. */
    static final List<String> KEYS = List.of("quality", "delivery", "communication", "pricing");

    Double get(String key) {
        return switch (key) {
            case "quality" -> quality;
            case "delivery" -> delivery;
            case "communication" -> communication;
            case "pricing" -> pricing;
            default -> throw new IllegalArgumentException("Unknown breakdown key " + key);
        };
    }
}

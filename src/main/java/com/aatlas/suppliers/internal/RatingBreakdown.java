package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The four bars under a rating, 1.0-5.0 each. Mirrors {@code RatingBreakdown} in the
 * frontend's {@code intel/suppliers.ts}.
 */
@Schema(name = "RatingBreakdown")
record RatingBreakdown(double quality, double delivery, double communication, double pricing) {

    /** The order the frontend iterates in; ties in "strongest" and "weakest" resolve by it. */
    static final List<String> KEYS = List.of("quality", "delivery", "communication", "pricing");

    double get(String key) {
        return switch (key) {
            case "quality" -> quality;
            case "delivery" -> delivery;
            case "communication" -> communication;
            case "pricing" -> pricing;
            default -> throw new IllegalArgumentException("Unknown breakdown key " + key);
        };
    }
}

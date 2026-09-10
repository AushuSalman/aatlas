package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** The stars, the breakdown behind them, and what to do about it. */
@Schema(name = "SupplierRatingResponse")
record RatingResponse(
        double rating,
        int reviewCount,
        String label,
        RatingBreakdown breakdown,
        String ratingSource,
        String recommendation) {
}

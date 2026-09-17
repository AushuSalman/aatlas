package com.aatlas.buy.internal;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The star rating a supplier carries on the panel: {@code supplier_ratings}, computed once at
 * seed time from real per-supplier facts (not a hash of a code on every request), breakdown
 * only over whichever dimensions are on file. {@code reviewCount} is the real count of
 * {@code supplier_reviews} rows - never a seeded range. A supplier with no rating row (a
 * custom one added only through a purchase order) is "not rated", never a placeholder.
 */
final class RatingEngine {

    private RatingEngine() {
    }

    record Profile(BigDecimal rating, int reviewCount, BigDecimal quality, BigDecimal delivery,
            BigDecimal communication, BigDecimal pricing, String label) {
    }

    static Optional<Profile> profileFor(SupplierGateway gateway, String supplierKey) {
        return gateway.rating(supplierKey).map(r -> new Profile(r.rating(), gateway.reviewCount(supplierKey),
                r.quality(), r.delivery(), r.communication(), r.pricing(),
                r.label() != null ? r.label() : ratingLabel(r.rating())));
    }

    static String ratingLabel(BigDecimal rating) {
        if (rating == null) {
            return null;
        }
        double v = rating.doubleValue();
        return v >= 4.5 ? "Excellent" : v >= 4 ? "Good" : v >= 3.3 ? "Fair" : "Weak";
    }
}

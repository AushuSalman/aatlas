package com.aatlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * An account as the frontend's {@code CustomerRecord} reads it. {@code id} is the code
 * the screens navigate by ({@code c-1}); the row's uuid stays internal.
 */
@Schema(name = "Customer")
record CustomerView(
        @Schema(example = "c-1") String id,
        @Schema(example = "Halloran Mechanical") String name,
        @Schema(allowableValues = {"contractor", "institutional", "industrial", "walk-in"}) String segment,
        @Schema(allowableValues = {"A", "B", "C"}) String tier,
        @Schema(description = "Standing contractual discount off the recommended price.") BigDecimal agreedDiscountPct,
        @Schema(description = "Seeds the quantity box so a quote opens on a realistic deal.") int typicalQty,
        String note,
        @Schema(allowableValues = {"urgent", "value", "enterprise", "repeat"}) String profile,
        int slaDays) {

    static CustomerView of(CustomerEntity customer) {
        return new CustomerView(
                customer.getCode(),
                customer.getName(),
                customer.getSegment(),
                customer.getTier(),
                customer.getAgreedDiscountPct(),
                customer.getTypicalQty(),
                customer.getNote(),
                customer.getProfile(),
                customer.getSlaDays());
    }
}

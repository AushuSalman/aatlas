package com.aatlas.rfq.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code POST /rfqs}'s body: an order (item, region, quantity, required days, priority) and
 * which suppliers from that order's ranked panel to invite. {@code destination} defaults to
 * the region's busiest branch, matching {@code buy.BuyIntelReader}.
 */
@Schema(name = "CreateRfqRequest")
record CreateRfqRequest(
        @NotBlank String itemNumber,
        @NotBlank String regionKey,
        String destinationId,
        @Min(1) int qty,
        @Min(1) int requiredDays,
        @NotBlank String priority,
        @NotNull @NotEmpty List<String> supplierIds,
        String notes,
        String incoterm,
        String paymentTerms) {
}

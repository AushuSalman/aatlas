package com.aatlas.decisions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ports the frontend's {@code recordPurchase} input ({@code intel/decisions.ts}): a purchase
 * decided in the browser, measured by the buy-side rule (at or under target is a win).
 * {@code followed} defaults to {@code agreedCost <= targetCost + 0.005} exactly as the
 * frontend does, unless given explicitly.
 *
 * <p>{@code supplierId}/{@code country}/{@code category} are not on the frontend's
 * {@code recordPurchase} - they are what {@link DecisionRecorder} additionally needs to also
 * badge a real line onto the {@code analytics} procurement ledger (see
 * {@code com.aatlas.analytics.RecordAward}) alongside writing the {@code deal} row. Omit them
 * to record the deal only.
 */
public record RecordPurchaseRequest(
        @NotBlank String itemNumber,
        String description,
        @NotBlank String supplierName,
        @Positive int qty,
        @NotNull BigDecimal cost,
        @NotNull BigDecimal targetCost,
        @NotNull BigDecimal agreedCost,
        LocalDate date,
        Boolean followed,
        String destinationId,
        UUID decisionId,
        String supplierId,
        String country,
        String category) {
}

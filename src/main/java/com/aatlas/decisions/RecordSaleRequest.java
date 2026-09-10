package com.aatlas.decisions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ports the frontend's {@code RecordSaleInput} ({@code platform/recorded.ts}, {@code recordSale}):
 * a sale recorded from the sell page. {@code followed} is derived (actualPrice >= suggestedPrice),
 * exactly as the frontend derives it - not accepted here.
 *
 * @param decisionId links this deal to a {@link Decision} already recorded through
 *     {@link DecisionRecorder#record}, when the caller made one - the frontend writes the two
 *     as separate localStorage entries from the same page action; this lets the Java port do
 *     it as one linked write instead.
 */
public record RecordSaleRequest(
        @NotBlank String itemNumber,
        String description,
        @NotBlank String storeName,
        String customerName,
        @Positive int qty,
        @NotNull BigDecimal cost,
        @NotNull BigDecimal baselinePrice,
        @NotNull BigDecimal suggestedPrice,
        @NotNull BigDecimal actualPrice,
        BigDecimal marginFloor,
        LocalDate date,
        UUID decisionId) {
}

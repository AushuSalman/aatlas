package com.aatlas.decisions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ports the frontend's {@code RecordSaleInput} ({@code platform/recorded.ts}, {@code recordSale}):
 * a sale recorded from the sell page. {@code followed} is derived (actualPrice >= suggestedPrice),
 * exactly as the frontend derives it - not accepted here.
 *
 * @param cost the unit cost on file, or {@code null} when the tenant has none - the sale is
 *     still recorded; only the realised-profit figures skip it
 * @param qty may be zero for a line with no sales volume on file yet
 * @param decisionId links this deal to a {@link Decision} already recorded through
 *     {@link DecisionRecorder#record}, when the caller made one - the frontend writes the two
 *     as separate localStorage entries from the same page action; this lets the Java port do
 *     it as one linked write instead.
 * @param destinationId the branch code the sale was priced at, so follow-rate can be read per
 *     store; {@code storeName} stays the display label
 */
public record RecordSaleRequest(
        @NotBlank String itemNumber,
        String description,
        @NotBlank String storeName,
        String customerName,
        @PositiveOrZero int qty,
        BigDecimal cost,
        @NotNull BigDecimal baselinePrice,
        @NotNull BigDecimal suggestedPrice,
        @NotNull BigDecimal actualPrice,
        BigDecimal marginFloor,
        LocalDate date,
        UUID decisionId,
        String destinationId) {
}

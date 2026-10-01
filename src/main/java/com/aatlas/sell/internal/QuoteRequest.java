package com.aatlas.sell.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * {@code POST /sell/quote} and {@code POST /sell/quotes}. {@code price} is read by {@code /quotes}
 * only: the unit price the sale was actually made at; absent, the quoted deal price is recorded.
 * {@code currency} is accepted and ignored by the engine: money on the wire is USD, and currency
 * display is the frontend's {@code money.ts}, not this API (API-BRIEF).
 */
@Schema(name = "QuoteRequest")
public record QuoteRequest(
        @NotBlank String item,
        @NotBlank String store,
        String customerId,
        @Min(1) Integer qty,
        @Schema(description = "POST /sell/quotes: the unit price the sale was made at. Absent, the quoted deal price.")
        @Positive BigDecimal price,
        String currency) {

    public int qtyOrDefault() {
        return qty == null ? 1 : qty;
    }
}

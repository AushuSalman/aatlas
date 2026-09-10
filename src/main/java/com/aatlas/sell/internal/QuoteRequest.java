package com.aatlas.sell.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /sell/quote} and {@code POST /sell/quotes}. {@code currency} is accepted and
 * ignored by the engine: money on the wire is USD, and currency display is the frontend's
 * {@code money.ts}, not this API (API-BRIEF).
 */
@Schema(name = "QuoteRequest")
public record QuoteRequest(
        @NotBlank String item,
        @NotBlank String store,
        String customerId,
        @Min(1) Integer qty,
        String currency) {

    public int qtyOrDefault() {
        return qty == null ? 1 : qty;
    }
}

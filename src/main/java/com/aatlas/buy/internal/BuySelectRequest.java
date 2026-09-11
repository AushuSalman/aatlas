package com.aatlas.buy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code POST /buy/select}'s body. The frontend's {@code BuySelectInput} in {@code
 * platform/backend.ts}.
 *
 * <p>{@code qty} is optional (defaults to 1 unit when omitted, so existing callers keep
 * working): when given, an immediately-approved award is written as a real deal onto the
 * {@code decisions} ledger with that quantity, instead of only into this module's own
 * {@code buy_decisions} audit row - see {@code BuyService#select}.
 */
@Schema(name = "BuySelectRequest")
record BuySelectRequest(
        @NotBlank String itemNumber,
        @NotBlank String regionKey,
        @NotBlank String destinationId,
        @NotBlank String optionKey,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal orderValue,
        Integer qty) {
}

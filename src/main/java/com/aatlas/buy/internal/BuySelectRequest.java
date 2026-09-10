package com.aatlas.buy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** {@code POST /buy/select}'s body. The frontend's {@code BuySelectInput} in {@code platform/backend.ts}. */
@Schema(name = "BuySelectRequest")
record BuySelectRequest(
        @NotBlank String itemNumber,
        @NotBlank String regionKey,
        @NotBlank String destinationId,
        @NotBlank String optionKey,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal orderValue) {
}

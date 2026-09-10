package com.aatlas.buy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /buy/what-if}'s body: the same four inputs {@code /buy/plan} takes, plus the scenario. */
@Schema(name = "BuyWhatIfRequest")
record BuyWhatIfRequest(
        @NotBlank String item,
        @NotBlank String region,
        @Min(1) int qty,
        @Min(1) int requiredDays,
        @NotBlank String priority,
        @NotBlank String scenario) {
}

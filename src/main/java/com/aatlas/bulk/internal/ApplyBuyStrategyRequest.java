package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Schema(name = "ApplyBuyStrategyRequest")
record ApplyBuyStrategyRequest(
        @Schema(example = "south") @NotBlank String regionKey,
        @NotEmpty List<@NotBlank String> items,
        @Schema(allowableValues = {"lowest-cost", "fastest", "lowest-risk", "balanced", "split"}, example = "balanced")
                @NotBlank
                @Pattern(regexp = "lowest-cost|fastest|lowest-risk|balanced|split", message = "Unknown strategy.")
                String strategyKey,
        @Schema(description = "Quarters of volume: 1 = quarter, 2 = half year, 4 = year.", example = "1")
                @Positive
                Double horizon) {

    ApplyBuyStrategyRequest {
        if (horizon == null) {
            horizon = 1.0;
        }
    }
}

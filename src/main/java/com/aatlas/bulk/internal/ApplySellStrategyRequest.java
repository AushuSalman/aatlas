package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

@Schema(name = "ApplySellStrategyRequest")
record ApplySellStrategyRequest(
        @Schema(example = "100959") @NotBlank String storeId,
        @NotEmpty List<@NotBlank String> items,
        @Schema(allowableValues = {"max-profit", "fast-movement", "balanced"}, example = "balanced")
                @NotBlank
                @Pattern(regexp = "max-profit|fast-movement|balanced", message = "Unknown strategy.")
                String strategyKey) {
}

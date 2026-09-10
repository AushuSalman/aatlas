package com.aatlas.sell.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * {@code POST /sell/scenario}. Give {@code pct} for a price-move simulation ({@code
 * runScenario}), {@code scenario} for a named what-if ({@code sellWhatIf}), or both - each
 * populates the matching field of the response.
 */
@Schema(name = "ScenarioRequest")
public record ScenarioRequest(
        @NotBlank String item,
        @NotBlank String store,
        @Schema(description = "Price move, percent. E.g. 5 for +5%.") BigDecimal pct,
        @Schema(description = "One of price-up-5, demand-down-10, competitor-cut-5, hold-30.") String scenario) {
}

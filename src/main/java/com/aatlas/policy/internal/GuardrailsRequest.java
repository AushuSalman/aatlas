package com.aatlas.policy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * The four limits as the Settings form posts them. All four every time: the form shows
 * all four, and a partial update would leave "which ones did they mean" to guesswork.
 *
 * <p>Ranges are {@link GuardrailLimits}, which mirrors the frontend's
 * {@code GUARDRAIL_FIELDS}; a violation is a 400 with a message per field.
 */
@Schema(name = "GuardrailsRequest", description = "All four limits, as percentages.")
record GuardrailsRequest(
        @Schema(example = "25")
                @NotNull(message = "Set a minimum margin.")
                @DecimalMin(value = GuardrailLimits.MIN_MARGIN_MIN, message = "Use a value between 5 and 60.")
                @DecimalMax(value = GuardrailLimits.MIN_MARGIN_MAX, message = "Use a value between 5 and 60.")
                BigDecimal minMarginPct,

        @Schema(example = "15")
                @NotNull(message = "Set a maximum discount.")
                @DecimalMin(value = GuardrailLimits.MAX_DISCOUNT_MIN, message = "Use a value between 0 and 40.")
                @DecimalMax(value = GuardrailLimits.MAX_DISCOUNT_MAX, message = "Use a value between 0 and 40.")
                BigDecimal maxDiscountPct,

        @Schema(example = "8")
                @NotNull(message = "Set a maximum speed premium.")
                @DecimalMin(value = GuardrailLimits.MAX_SPEED_PREMIUM_MIN, message = "Use a value between 0 and 25.")
                @DecimalMax(value = GuardrailLimits.MAX_SPEED_PREMIUM_MAX, message = "Use a value between 0 and 25.")
                BigDecimal maxSpeedPremiumPct,

        @Schema(example = "10")
                @NotNull(message = "Set a maximum above market.")
                @DecimalMin(value = GuardrailLimits.MAX_MARKET_DEVIATION_MIN, message = "Use a value between 0 and 30.")
                @DecimalMax(value = GuardrailLimits.MAX_MARKET_DEVIATION_MAX, message = "Use a value between 0 and 30.")
                BigDecimal maxMarketDeviationPct) {

    GuardrailValues toValues() {
        return new GuardrailValues(minMarginPct, maxDiscountPct, maxSpeedPremiumPct, maxMarketDeviationPct);
    }
}

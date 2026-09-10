package com.aatlas.sell.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** {@code POST /sell/apply}. {@code price} overrides the guardrail-adjusted recommendation when given. */
@Schema(name = "ApplyRequest")
public record ApplyRequest(@NotBlank String item, @NotBlank String store, BigDecimal price) {
}

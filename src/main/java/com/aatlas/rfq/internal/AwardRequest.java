package com.aatlas.rfq.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /rfqs/{id}/award}'s body. */
@Schema(name = "AwardRequest")
record AwardRequest(@NotBlank String supplierId) {
}

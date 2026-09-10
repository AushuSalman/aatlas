package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Adds a supplier already looked up (and shown to the buyer) to the panel. */
@Schema(name = "AddSupplierRequest")
record AddSupplierRequest(
        @Schema(description = "The id returned by POST /suppliers/lookup.")
                @NotNull(message = "A lookup id is needed to add a supplier.")
                UUID lookupId) {
}

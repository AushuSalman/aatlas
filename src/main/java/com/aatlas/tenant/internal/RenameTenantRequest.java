package com.aatlas.tenant.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one field {@code PATCH /tenant} accepts today; plan changes arrive with billing. */
@Schema(name = "RenameTenantRequest")
record RenameTenantRequest(
        @Schema(example = "Kestrel Supply Co.")
                @NotBlank(message = "The company needs a name.")
                @Size(max = 200, message = "That company name is too long.")
                String name) {
}

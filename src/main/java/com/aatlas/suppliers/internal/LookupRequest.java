package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Pull their information from the web": a name, a URL, or a name and a country. */
@Schema(name = "SupplierLookupRequest")
record LookupRequest(
        @Schema(example = "Halden Metals Ltd")
                @NotBlank(message = "A company name or website is needed to look up a supplier.")
                @Size(max = 200, message = "That query is too long.")
                String query,
        @Schema(example = "UK") String country) {
}

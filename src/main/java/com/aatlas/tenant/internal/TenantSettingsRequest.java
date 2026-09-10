package com.aatlas.tenant.internal;

import com.aatlas.tenant.CountryCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Either field may be omitted. A country alone also picks that country's currency; a
 * currency alone keeps the country. Neither is a 400.
 *
 * @param country US or UK
 * @param currency one of the ten codes {@code GET /reference/currencies} lists
 */
@Schema(name = "TenantSettingsRequest")
record TenantSettingsRequest(
        @Schema(example = "UK") CountryCode country,
        @Schema(example = "EUR") @Size(min = 3, max = 3, message = "A currency is a three-letter code.") String currency) {
}

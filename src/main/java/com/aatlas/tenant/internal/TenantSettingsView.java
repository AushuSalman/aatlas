package com.aatlas.tenant.internal;

import com.aatlas.tenant.CountryCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The locale a tenant works in, with the words the screens need alongside it: "State" or
 * "Region" for a subdivision, "Region" or "Territory" for one of the four market regions.
 * Mirrors what the frontend's {@code locale.ts} derives from {@code COUNTRIES}.
 */
@Schema(name = "TenantSettings")
record TenantSettingsView(
        CountryCode country,
        String currency,
        String subdivisionNoun,
        String subdivisionNounPlural,
        String regionNoun) {
}

package com.aatlas.identity.internal;

import com.aatlas.policy.Persona;
import com.aatlas.tenant.CountryCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Everything the shell needs on load: who, where, what they may do, and whether there is
 * data yet. The frontend's {@code Session} plus its {@code Persona}, in one round trip.
 *
 * @param dataSource null until onboarding has connected one, which routes the client to
 *     onboarding rather than the workspace
 */
@Schema(name = "Me")
record MeResponse(
        AuthResponse.UserView user,
        String company,
        CountryCode country,
        String currency,
        Persona persona,
        DataSourceView dataSource) {
}

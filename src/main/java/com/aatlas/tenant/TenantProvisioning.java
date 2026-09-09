package com.aatlas.tenant;

import java.util.UUID;

/**
 * Creating a company, for the one caller that has to: signup.
 *
 * <p>This interface exists so {@code identity} can create a tenant without reaching into
 * this module's entities - {@code ModularityTests} would fail the build if it did, and
 * more importantly the tenant table's invariants (slug uniqueness, the country-to-currency
 * pairing) belong to the module that owns the table.
 *
 * <p>Implementations join the caller's transaction. Signup writes a tenant and its first
 * user together or writes neither.
 */
public interface TenantProvisioning {

    /**
     * Creates a company and returns it.
     *
     * @throws com.aatlas.common.error.ApiException if no usable slug can be derived from
     *     the name, which in practice means a name with no alphanumeric characters at all
     */
    TenantView provision(NewTenant command);

    /** The company as other modules see it. Never exposes the entity. */
    record TenantView(UUID id, String name, String slug, CountryCode country, String tradingCurrency) {
    }

    /**
     * What signup knows at the moment it creates the company.
     *
     * @param name the company as typed. Blank is allowed and falls back to a default,
     *     because the signup form marks Company optional.
     * @param country selects the map, the regions and the trading currency
     */
    record NewTenant(String name, CountryCode country) {
    }
}

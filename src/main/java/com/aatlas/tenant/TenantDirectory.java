package com.aatlas.tenant;

import java.time.Instant;
import java.util.UUID;

/**
 * Reading a company, for the modules that render one: sign-in builds the session from
 * it, {@code /me} shows it in the header.
 *
 * <p>Read-only on purpose. Renaming and settings changes go through this module's own
 * endpoints, where the seat check lives; another module that could write the tenant row
 * would be a second place for that rule to drift.
 */
public interface TenantDirectory {

    /**
     * @throws com.aatlas.common.error.ApiException 404 if there is no such tenant, which
     *     for a caller holding a signed JWT means the company was deleted under them
     */
    TenantInfo get(UUID tenantId);

    /**
     * The company as other modules see it: profile plus the two settings every screen
     * needs. Country and currency come from {@code tenant_settings}, the source of truth.
     */
    record TenantInfo(
            UUID id,
            String name,
            String slug,
            String status,
            CountryCode country,
            String tradingCurrency,
            Instant createdAt) {
    }
}

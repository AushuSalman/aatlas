package com.aatlas.suppliers;

import java.util.UUID;

/**
 * Copies the seeded supplier panel into a tenant.
 *
 * <p>The public seam for the one thing another module has to be able to do with suppliers
 * without reaching into this one: give a tenant its starting panel when the sample data
 * source is connected. The seeder reads {@code seed/suppliers.json} - the eight suppliers
 * the prototype ships with, their terms and their profiles - and writes the supplier row,
 * terms, rating, reviews, risk and six months of on-time history for each.
 *
 * <p>Idempotent. A tenant that already holds the seeded panel gets nothing added, so it is
 * safe for the connect event and the {@code POST /api/v1/suppliers/seed} endpoint to both
 * fire.
 */
public interface SupplierPanelSeeder {

    /**
     * Seeds the panel for {@code tenantId}.
     *
     * @return how many suppliers were added: eight on a fresh tenant, zero when the panel
     *     was already there
     */
    int seedForTenant(UUID tenantId);
}

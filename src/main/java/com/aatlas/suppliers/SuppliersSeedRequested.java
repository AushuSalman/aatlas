package com.aatlas.suppliers;

import java.util.UUID;

/**
 * Ask for a tenant's supplier panel to be seeded.
 *
 * <p>A stand-in. The real trigger is {@code com.aatlas.ingest.SampleDataConnected}, published
 * by the ingest module when a tenant connects the sample data source; that class lives in
 * another builder's worktree, so the listener in this module is written against this local
 * record for now.
 *
 * <p>TODO(merge): retarget {@code SuppliersSeedListener} at {@code com.aatlas.ingest.SampleDataConnected}
 * and delete this record.
 *
 * @param tenantId the tenant whose panel should be seeded
 */
public record SuppliersSeedRequested(UUID tenantId) {
}

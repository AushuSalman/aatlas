package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a tenant's data comes from, for the session and {@code /me}.
 *
 * <p>Null is load-bearing on the wire: a tenant with no source is routed to onboarding
 * rather than the workspace, because a pricing tool with no transaction history has
 * nothing to recommend from.
 *
 * <p>The seam between this module and {@code ingest}, which owns the {@code data_sources}
 * table (its V7). {@link IngestDataSourceLookup} is the one implementation, reached
 * through that module's public {@code SampleDataProvisioner#current}.
 */
interface DataSourceLookup {

    /** The tenant's current source, newest connection first, if any. */
    Optional<DataSourceView> current(UUID tenantId);
}

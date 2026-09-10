package com.aatlas.ingest;

import java.util.UUID;

/**
 * Connecting the sample dataset to a tenant: the seeded catalogue copied in, a
 * {@code data_sources} row recorded, and {@link SampleDataConnected} published.
 *
 * <p>The public face of the ingest module's onboarding path. The REST endpoint uses it
 * for the signed-in tenant; a demo or support job may call it for any tenant inside
 * {@code TenantContext.runAs}.
 */
public interface SampleDataProvisioner {

    /**
     * Connects the sample dataset. The tenant's country (from its profile) decides which
     * branch network it receives.
     *
     * @param tenantId the tenant to provision
     * @param connectedBy the user who asked, or {@code null} for a system job
     * @return the recorded source
     * @throws com.aatlas.common.error.ApiException {@code 409 already_connected} when the
     *     tenant already has the sample dataset
     */
    DataSourceView connectSampleData(UUID tenantId, UUID connectedBy);
}

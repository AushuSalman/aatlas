package com.aatlas.identity.internal;

import com.aatlas.ingest.SampleDataProvisioner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link DataSourceLookup} backed by the {@code ingest} module's real table, reached
 * through its public {@link SampleDataProvisioner#current}. Maps that module's wire
 * shape - which also carries an id, a sync status and a last-sync time - onto this
 * module's {@link DataSourceView}, the frontend's smaller {@code DataSource} type: kind,
 * label, detail, connected-at.
 */
@Component
class IngestDataSourceLookup implements DataSourceLookup {

    private final SampleDataProvisioner provisioner;

    IngestDataSourceLookup(SampleDataProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public Optional<DataSourceView> current(UUID tenantId) {
        return provisioner.current(tenantId).map(v -> new DataSourceView(v.kind(), v.label(), v.detail(), v.connectedAt()));
    }
}

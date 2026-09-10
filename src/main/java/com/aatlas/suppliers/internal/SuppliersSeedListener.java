package com.aatlas.suppliers.internal;

import com.aatlas.ingest.SampleDataConnected;
import com.aatlas.suppliers.SupplierPanelSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a tenant's supplier panel when the sample data source is connected.
 *
 * <p>Listens for {@code ingest}'s real event (retargeted at merge time from a local
 * stand-in written before the {@code ingest} module existed in this worktree).
 * {@code POST /api/v1/suppliers/seed}, which calls {@link SupplierPanelSeeder} directly,
 * stays available to re-seed a tenant by hand.
 */
@Component
class SuppliersSeedListener {

    private static final Logger log = LoggerFactory.getLogger(SuppliersSeedListener.class);

    private final SupplierPanelSeeder seeder;

    SuppliersSeedListener(SupplierPanelSeeder seeder) {
        this.seeder = seeder;
    }

    @ApplicationModuleListener
    void on(SampleDataConnected event) {
        int added = seeder.seedForTenant(event.tenantId());
        log.info("Suppliers seeded for tenant {} after SampleDataConnected: {} added", event.tenantId(), added);
    }
}

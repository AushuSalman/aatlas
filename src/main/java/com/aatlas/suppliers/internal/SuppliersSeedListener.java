package com.aatlas.suppliers.internal;

import com.aatlas.suppliers.SupplierPanelSeeder;
import com.aatlas.suppliers.SuppliersSeedRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Seeds a tenant's supplier panel when the sample data source is connected.
 *
 * <p>TODO(merge): retarget this listener at {@code com.aatlas.ingest.SampleDataConnected}
 * (published by the ingest module) and delete {@link SuppliersSeedRequested}.
 */
@Component
class SuppliersSeedListener {

    private static final Logger log = LoggerFactory.getLogger(SuppliersSeedListener.class);

    private final SupplierPanelSeeder seeder;

    SuppliersSeedListener(SupplierPanelSeeder seeder) {
        this.seeder = seeder;
    }

    @ApplicationModuleListener
    void on(SuppliersSeedRequested event) {
        int added = seeder.seedForTenant(event.tenantId());
        log.info("Suppliers seeded for tenant {} after SuppliersSeedRequested: {} added", event.tenantId(), added);
    }
}

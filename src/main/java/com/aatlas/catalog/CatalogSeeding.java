package com.aatlas.catalog;

import com.aatlas.tenant.CountryCode;
import java.util.UUID;

/**
 * Filling a tenant's catalogue from the seed files, for the one caller that has to:
 * the sample data source in {@code ingest}.
 *
 * <p>This interface exists so {@code ingest} can populate stores, products, product
 * history and customers without reaching into this module's entities - {@code
 * ModularityTests} would fail the build if it did, and the catalogue's invariants (one
 * item number per tenant, which branches sell which item) belong to the module that owns
 * the tables.
 *
 * <p>Implementations join the caller's transaction, so the catalogue and the data-source
 * row that describes it commit together or not at all.
 */
public interface CatalogSeeding {

    /**
     * Copies the seeded catalogue for {@code country} into the tenant. Idempotent: a
     * tenant that already has a catalogue is left exactly as it is and the summary says so,
     * so reconnecting the sample source after a disconnect does not duplicate anything.
     */
    SeedSummary seedSampleCatalogue(UUID tenantId, CountryCode country);

    /**
     * What the seed put in place, or found already there.
     *
     * @param alreadyPresent true when nothing was written because the tenant had a catalogue
     */
    record SeedSummary(int stores, int products, int productStores, int customers, boolean alreadyPresent) {

        public static SeedSummary existing(int stores, int products, int productStores, int customers) {
            return new SeedSummary(stores, products, productStores, customers, true);
        }
    }
}

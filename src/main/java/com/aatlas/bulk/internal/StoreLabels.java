package com.aatlas.bulk.internal;

import com.aatlas.bulk.internal.BulkSeedCatalog.SeedStore;

/**
 * Exact port of {@code src/lib/intel/catalog.ts}'s {@code storeCity}/{@code storeLabel}:
 * "Dallas" from "Dallas-Fort Worth-Arlington", and "Dallas #100959" from that.
 */
final class StoreLabels {

    private StoreLabels() {
    }

    static String city(BulkSeedCatalog catalog, String storeId) {
        SeedStore t = catalog.store(storeId).orElse(null);
        if (t == null) {
            return storeId;
        }
        String source = t.msaName() != null ? t.msaName() : t.legalName();
        if (source == null) {
            return storeId;
        }
        String first = source.split("-")[0];
        return first.replace(" Branch", "").trim();
    }

    static String of(BulkSeedCatalog catalog, String storeId) {
        return city(catalog, storeId) + " #" + storeId;
    }
}

package com.aatlas.bulk.internal;

import com.aatlas.history.Catalogue;

/**
 * "Dallas #100959" for a branch, from the tenant's real store row. {@code StoreRef.label()}
 * already does exactly this (same "first segment before the dash, minus ' Branch'" rule
 * bulk used to port from the frontend by hand), so this is now a one-line adapter rather
 * than its own port.
 */
final class StoreLabels {

    private StoreLabels() {
    }

    static String of(Catalogue catalogue, String storeId) {
        return catalogue.store(storeId).map(Catalogue.StoreRef::label).orElse(storeId);
    }
}

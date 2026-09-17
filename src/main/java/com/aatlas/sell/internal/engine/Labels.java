package com.aatlas.sell.internal.engine;

import com.aatlas.history.Catalogue.StoreRef;

/** {@code storeName} from {@code src/lib/platform/data.ts}. */
final class Labels {

    private Labels() {
    }

    /** {@code storeName} in {@code src/lib/platform/data.ts}: "Dallas — TX", not "Dallas #100959". */
    static String dataStoreName(String storeId, StoreRef store) {
        if (store == null) {
            return storeId;
        }
        String source = store.msaName() != null ? store.msaName() : "Branch";
        String first = source.split("-")[0];
        String state = store.subdivisionCode() != null ? store.subdivisionCode() : "";
        return first + " — " + state;
    }
}

package com.aatlas.sell.internal.engine;

import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;

/** {@code storeCity}/{@code storeLabel} from {@code src/lib/intel/catalog.ts}. */
final class Labels {

    private Labels() {
    }

    static String storeCity(String storeId, StoreRef store) {
        if (store == null) {
            return storeId;
        }
        String source = store.msaName() != null ? store.msaName() : store.legalName();
        if (source == null) {
            return storeId;
        }
        String first = source.split("-")[0];
        return first.replace(" Branch", "").trim();
    }

    static String storeLabel(String storeId, StoreRef store) {
        return storeCity(storeId, store) + " #" + storeId;
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

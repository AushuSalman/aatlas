package com.aatlas.insights.internal;

import java.util.Comparator;
import java.util.List;

/**
 * The frontend's fixture declaration order for branches and products - {@code TENANTS_US}
 * in {@code mock/catalog.ts} and {@code PRODUCTS} (filtered to {@code hasSales}) in the
 * same file - which is not alphabetical or numeric.
 *
 * <p>Every number this module produces is order-independent (a sum, an average, a sorted
 * top-N by value), except a handful of TypeScript {@code Set}s that are read back as
 * "the first one or two members" or spread into an array verbatim: which branch is named
 * first when several are overstocked, which item is named first when several have thin
 * margins, the order {@code raisePrices}/{@code increaseInventory}/{@code reviewSuppliers}
 * come back in, and stable-sort ties when several products share the same integer score.
 * Those all depend on the order the TypeScript walked {@code TENANTS}/
 * {@code SELLABLE_PRODUCTS} while building the set, so the engines here walk this same
 * order wherever that matters, rather than the branch/product tables' natural (code-sorted)
 * order used everywhere else.
 */
final class FixtureOrder {

    private FixtureOrder() {
    }

    private static final List<String> STORE_ORDER = List.of(
            "100349", "100047", "100117", "100649", "100812", "100205", "100933", "100571", "100959");

    private static final List<String> PRODUCT_ORDER = List.of(
            "HRD304148", "HRD118902", "HRD772310", "HRD450871", "HRD290145", "HRD661204",
            "HRD983377", "HRD512066", "HRD874019", "HRD335590", "HRD107744", "HRD248813");

    static List<StoreRef> stores(CatalogSnapshot snapshot) {
        return snapshot.allStoresOrdered().stream().sorted(byIndex(STORE_ORDER, StoreRef::storeCode)).toList();
    }

    static List<StoreRef> stores(List<StoreRef> stores) {
        return stores.stream().sorted(byIndex(STORE_ORDER, StoreRef::storeCode)).toList();
    }

    static List<ProductRef> sellableProducts(CatalogSnapshot snapshot) {
        return snapshot.sellableProducts().stream().sorted(byIndex(PRODUCT_ORDER, ProductRef::itemNumber)).toList();
    }

    private static <T> Comparator<T> byIndex(List<String> order, java.util.function.Function<T, String> key) {
        return Comparator.comparingInt(t -> {
            int i = order.indexOf(key.apply(t));
            return i < 0 ? Integer.MAX_VALUE : i;
        });
    }
}

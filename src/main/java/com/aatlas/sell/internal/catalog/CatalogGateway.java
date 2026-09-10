package com.aatlas.sell.internal.catalog;

import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.RegionRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the current tenant's catalogue, for the pricing and sell engines.
 *
 * <p>See {@link CatalogRefs} for why this reads catalog's tables directly rather than a
 * public reader on that module - one does not exist yet.
 */
public interface CatalogGateway {

    Optional<ProductRef> findProduct(String itemNumber);

    /** Sellable products (has_sales = true), in no particular order. */
    List<ProductRef> sellableProducts();

    Optional<StoreRef> findStore(String storeCode);

    List<StoreRef> allStores();

    /** Store codes with a {@code product_stores} row for this item where {@code sells = true}. */
    List<String> sellerCodesOf(String itemNumber);

    /** Whether this item has sales history at this store - the priceability test itself. */
    boolean sells(String itemNumber, String storeCode);

    Optional<CustomerRef> findCustomer(String code);

    List<CustomerRef> allCustomers();

    /** The market region a store's {@code region_key} names, in this tenant's country. */
    Optional<RegionRef> regionOf(String regionKey);

    List<RegionRef> allRegions();

    /** The country this tenant's branches are in ("US" or "UK"); "US" when there are none yet. */
    String tenantCountry();

    CommodityTrend commodityTrend(String commodityKey);
}

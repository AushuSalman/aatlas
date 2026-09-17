package com.aatlas.sell.internal.catalog;

import com.aatlas.history.Catalogue.CustomerRef;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.Catalogue.RegionRef;
import com.aatlas.history.Catalogue.StoreRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to the current tenant's catalogue, for the pricing and sell engines.
 *
 * <p>Thin wrapper over {@code history.Catalogue}/{@code history.Reference}: the catalogue
 * rows (products, branches, customers, regions) and the commodity reference table, read
 * once through the module that owns the tenant-scoped JDBC for them.
 */
public interface CatalogGateway {

    Optional<ProductRef> findProduct(String itemNumber);

    /** Sellable products ({@code has_sales = true}), in no particular order. */
    List<ProductRef> sellableProducts();

    List<ProductRef> allProducts();

    Optional<StoreRef> findStore(String storeCode);

    List<StoreRef> allStores();

    Optional<CustomerRef> findCustomer(String code);

    List<CustomerRef> allCustomers();

    /** The market region a store's {@code region_key} names, in this tenant's country. */
    Optional<RegionRef> regionOf(String regionKey);

    List<RegionRef> allRegions();

    /** The country this tenant's branches are in ("US" or "UK"); "US" when there are none yet. */
    String tenantCountry();

    CommodityTrend commodityTrend(String commodityKey);

    /**
     * The real customers who bought this (product, store) pair in the window, most units
     * first - what {@code AtpEngine.lines} allocates against. Empty when nobody has.
     */
    List<TopCustomer> topCustomersFor(UUID productId, UUID storeId, int limit, LocalDate from, LocalDate to);

    record TopCustomer(CustomerRef customer, BigDecimal units, long orders) {
    }
}

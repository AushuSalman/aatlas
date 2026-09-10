package com.aatlas.sell.internal.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read models for the catalogue rows the sell engines need, shaped after the frontend's
 * {@code ProductOption}, {@code StoreItem} and {@code CustomerRecord} (in {@code
 * src/lib/types.ts} and {@code src/lib/platform/data.ts}).
 *
 * <p>{@code catalog} does not yet expose a public reader for these (only {@link
 * com.aatlas.catalog.CatalogSeeding}, a write seam for {@code ingest}). Rather than block on
 * that, {@link CatalogGateway} reads the {@code products}/{@code stores}/{@code
 * product_stores}/{@code customers}/{@code regions}/{@code commodities} tables wave 1's
 * catalog module already owns directly - real rows, tenant-scoped exactly like every other
 * query in this codebase, never catalog's {@code internal} Java types.
 *
 * <p>TODO(merge): once {@code catalog} grows a package-root reader (a {@code
 * ProductReader}/{@code StoreReader} alongside {@code CatalogSeeding}), retarget {@link
 * CatalogGatewayImpl} to call it instead of running these queries here.
 */
public final class CatalogRefs {

    private CatalogRefs() {
    }

    public record ProductRef(
            String itemNumber,
            String description,
            String shortName,
            String category,
            String subcategory,
            String commodity,
            String unit,
            boolean hasSales,
            String defaultStoreCode) {
    }

    public record StoreRef(
            String storeCode,
            String companyNumber,
            String legalName,
            String country,
            String subdivisionCode,
            String msaName,
            BigDecimal rpp,
            Integer txns,
            Integer itemCount,
            String segment,
            String regionKey) {
    }

    public record CustomerRef(
            String code,
            String name,
            String segment,
            String tier,
            BigDecimal agreedDiscountPct,
            int typicalQty,
            String profile,
            int slaDays,
            String note) {
    }

    /** A market region as the tenant's country names it: {@code regions.short_label} is the
     *  compass word ("South"), {@code regions.label} the full name ("South & London"). */
    public record RegionRef(String key, String compassLabel, String fullName) {
    }

    public record CommodityTrend(double pct90, String label) {
    }

    /** Everything {@code getPricingModel} needs about one (item, store) pair in one read. */
    public record Selection(ProductRef product, StoreRef store, boolean sells, List<String> sellerCodes) {
    }
}

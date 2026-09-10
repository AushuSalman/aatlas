package com.aatlas.insights.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the intelligence engines in this module read about the tenant's catalogue and
 * the reference data behind it, loaded once per request rather than row by row.
 *
 * <p>This mirrors what the frontend held as static, in-memory constants
 * ({@code TENANTS}, {@code PRODUCTS}, {@code SUPPLIERS}, {@code MARKET_REGIONS},
 * {@code COMMODITY_TREND}, {@code REGIONS}/{@code ORIGINS} in {@code logistics.ts}) - the
 * engines below are pure functions of this snapshot plus the seeded hash, exactly as the
 * TypeScript functions were pure functions of those constants plus {@code rand}.
 *
 * <p>Nothing here is a snapshot TABLE: it is read fresh from wave 1's {@code catalog} and
 * {@code suppliers} tables (and the shared reference tables both modules loaded at
 * startup) on every request, through this module's own read-only queries - {@code catalog}
 * and {@code suppliers} do not yet publish a reader in their public API (only a seeding
 * interface each), so rather than reach into their {@code internal} packages (which
 * {@code ModularityTests} forbids), this module reads the same physical tables directly,
 * the way {@code common/seed/Seeded} reads a shared hash formula rather than a shared
 * service.
 */
record CatalogSnapshot(
        List<ProductRef> products,
        Map<String, StoreRef> storesByCode,
        Map<String, java.util.Set<String>> sellersByItem,
        List<SupplierRef> suppliers,
        Map<String, CommodityRef> commodityTrend,
        List<MarketRegionRef> marketRegions,
        Map<String, String> subdivisionNames,
        List<LogisticsLaneRef> logisticsLanes,
        Map<String, LogisticsOriginRef> logisticsOrigins) {

    /** Products with sales history somewhere - the ones a price can be produced for. */
    List<ProductRef> sellableProducts() {
        return products.stream().filter(ProductRef::hasSales).toList();
    }

    Optional<ProductRef> product(String itemNumber) {
        return products.stream().filter(p -> p.itemNumber().equals(itemNumber)).findFirst();
    }

    Optional<StoreRef> store(String storeCode) {
        return Optional.ofNullable(storesByCode.get(storeCode));
    }

    List<StoreRef> allStoresOrdered() {
        return storesByCode.values().stream()
                .sorted(java.util.Comparator.comparing(StoreRef::storeCode))
                .toList();
    }

    boolean priceable(String itemNumber, String storeCode) {
        java.util.Set<String> sellers = sellersByItem.get(itemNumber);
        return sellers != null && sellers.contains(storeCode);
    }

    /** The market region (south/west/north/east) a US state or UK region sits in. */
    MarketRegionRef marketRegionForState(String state) {
        return marketRegions.stream()
                .filter(r -> state != null && r.states().contains(state))
                .findFirst()
                .or(() -> marketRegions.stream().filter(r -> r.key().equals("north")).findFirst())
                .orElse(marketRegions.get(0));
    }

    MarketRegionRef marketRegion(String key) {
        return marketRegions.stream().filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown market region " + key));
    }

    CommodityRef commodity(String key) {
        return commodityTrend.getOrDefault(key, new CommodityRef(BigDecimal.ZERO, "No commodity exposure"));
    }

    Optional<StoreRef> storeByCodeOrId(String idOrCode) {
        return store(idOrCode).or(() -> storesByCode.values().stream()
                .filter(s -> idOrCode != null && idOrCode.equals(String.valueOf(s.id())))
                .findFirst());
    }
}

/** A product row, carrying exactly what {@code intel/catalog.ts}'s {@code ProductMeta} plus {@code ProductOption} do. */
record ProductRef(
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

/** A branch row: what {@code mock/catalog.ts}'s {@code StoreItem} plus country/region carry. */
record StoreRef(
        java.util.UUID id,
        String storeCode,
        String legalName,
        String country,
        String state,
        String msaName,
        BigDecimal rpp,
        Integer txns,
        String segment,
        String regionKey) {
}

/** The subset of {@code platform/data.ts}'s {@code SupplierRecord} the buy-side engine needs. */
record SupplierRef(String id, String name, String country, int leadTimeDays, double otifPct, double priceIndex) {
}

/** {@code intel/catalog.ts}'s {@code COMMODITY_TREND} entry. */
record CommodityRef(BigDecimal pct90, String label) {

    double pct90AsDouble() {
        return pct90 == null ? 0 : pct90.doubleValue();
    }
}

/** {@code intel/catalog.ts}'s {@code MarketRegion}: the four south/west/north/east regions. */
record MarketRegionRef(String key, String shortLabel, String fullLabel, List<String> states) {
}

/** One row of {@code platform/logistics.ts}'s {@code REGIONS}: a freight lane region. */
record LogisticsLaneRef(
        String key,
        String label,
        List<String> states,
        Map<String, BigDecimal> inlandPct,
        Map<String, Integer> inlandDays) {
}

/** A value of {@code platform/logistics.ts}'s {@code ORIGINS}. */
record LogisticsOriginRef(
        String entry, String mode, String gateway, BigDecimal inboundPct, int inboundDays, BigDecimal dutyPct) {
}

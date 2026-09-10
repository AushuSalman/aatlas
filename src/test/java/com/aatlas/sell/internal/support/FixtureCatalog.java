package com.aatlas.sell.internal.support;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.RegionRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The same 15 products, 9 US branches, 4 market regions, 7 commodities and 8 customers as
 * {@code src/lib/mock/catalog.ts} (US locale) / {@code intel/catalog.ts} / {@code
 * platform/data.ts} - held in memory, no Postgres, no {@code TenantContext}.
 *
 * <p>Golden-file tests want a fast, deterministic {@link CatalogGateway} the same way {@code
 * SupplierEngineGoldenTest} calls {@code SupplierScoring}'s static methods directly with
 * literal inputs rather than standing up a database; this is that, for the engines that read
 * the catalogue through a gateway instead of taking primitives. The "which branches sell
 * this item" rule is {@code tenantsSellingItem} ported inline (bit shift on {@link
 * Seeded#hashString}), not a reach into {@code catalog.internal}.
 */
public final class FixtureCatalog implements CatalogGateway {

    /** {@code item_number, description, shortName, category, subcategory, commodity, unit, hasSales, defaultStoreCode} */
    private record P(String item, String desc, String shortName, String category, String subcategory,
            String commodity, String unit, boolean hasSales, String defaultStore) {
    }

    /** Seed order - matches {@code PRODUCTS} in mock/catalog.ts exactly (index-sensitive picks depend on it). */
    static final List<P> PRODUCTS = List.of(
            new P("HRD304148", "3/4 IN CPVC 90 DEG ELBOW SOCKET", "CPVC Elbow 3/4\"", "Plumbing", "Fittings", "pvc", "each", true, "100349"),
            new P("HRD118902", "1/2 IN COPPER TYPE L HARD TUBE 10FT", "Copper Tube 1/2\" Type L", "Plumbing", "Pipe & tube", "copper", "10 ft length", true, "100959"),
            new P("HRD772310", "3/4 IN BRASS BALL VALVE FULL PORT THREADED", "Brass Ball Valve 3/4\"", "Plumbing", "Valves", "brass", "each", true, "100117"),
            new P("HRD450871", "40 GAL NATURAL GAS WATER HEATER 40 MBH", "Gas Water Heater 40 gal", "Water heating", "Tank heaters", "equipment", "each", true, "100649"),
            new P("HRD290145", "2 IN PVC DWV SANITARY TEE HUB", "PVC Sanitary Tee 2\"", "Plumbing", "Fittings", "pvc", "each", true, "100812"),
            new P("HRD661204", "1 IN PEX-A EXPANSION COUPLING BRASS", "PEX-A Coupling 1\"", "Plumbing", "PEX", "pex", "each", true, "100205"),
            new P("HRD983377", "3 TON 14 SEER AC CONDENSING UNIT R-410A", "AC Condensing Unit 3 ton", "HVAC", "Cooling", "equipment", "each", true, "100933"),
            new P("HRD512066", "1/2 IN x 300FT PEX-B TUBING RED", "PEX-B Tubing 1/2\" (300 ft)", "Plumbing", "PEX", "pex", "coil", true, "100349"),
            new P("HRD874019", "4 IN CAST IRON NO-HUB COUPLING STAINLESS", "Cast Iron Coupling 4\"", "Plumbing", "Fittings", "iron", "each", true, "100047"),
            new P("HRD335590", "CHROME LAVATORY FAUCET 4 IN CENTERSET 2-HANDLE", "Lavatory Faucet Chrome", "Fixtures", "Faucets", "brass", "each", true, "100571"),
            new P("HRD107744", "3/4 IN x 100FT SOFT COPPER COIL TYPE L", "Copper Coil 3/4\" (100 ft)", "Plumbing", "Pipe & tube", "copper", "coil", true, "100649"),
            new P("HRD248813", "1-1/4 IN GALVANIZED STEEL NIPPLE 6 IN", "Steel Nipple 1-1/4\"", "Plumbing", "Fittings", "steel", "each", true, "100812"),
            new P("HRD900001", "6 IN DUCTILE IRON MJ GATE VALVE (NEW SKU)", "Ductile Iron Gate Valve 6\"", "Plumbing", "Valves", "iron", "each", false, null),
            new P("HRD900002", "SMART THERMOSTAT WIFI 24V C-WIRE (NEW SKU)", "Smart Thermostat Wi-Fi", "HVAC", "Controls", "equipment", "each", false, null),
            new P("HRD900003", "2 IN STAINLESS PRESS COUPLING 316L (NEW SKU)", "Stainless Press Coupling 2\"", "Plumbing", "Fittings", "steel", "each", false, null));

    /** {@code store_code, legal_name, subdivision_code(state), msa_name, rpp, txns, item_count, segment, region_key} */
    private record S(String code, String legalName, String state, String msa, BigDecimal rpp, int txns,
            int itemCount, String segment, String region) {
    }

    static final List<S> STORES = List.of(
            new S("100349", "Phoenix Branch", "AZ", "Phoenix-Mesa-Chandler", new BigDecimal("102.4"), 18432, 2140, "regular", "west"),
            new S("100047", "Dayton Branch", "OH", "Dayton-Kettering", new BigDecimal("89.7"), 24907, 3115, "regular", "north"),
            new S("100117", "Austin Branch", "TX", "Austin-Round Rock-Georgetown", new BigDecimal("97.1"), 12088, 1876, "occasional", "south"),
            new S("100649", "Seattle Branch", "WA", "Seattle-Tacoma-Bellevue", new BigDecimal("111.3"), 9654, 1502, "regular", "west"),
            new S("100812", "Nashville Branch", "TN", "Nashville-Davidson-Murfreesboro", new BigDecimal("94.2"), 15221, 2308, "occasional", "south"),
            new S("100205", "Charlotte Branch", "NC", "Charlotte-Concord-Gastonia", new BigDecimal("96.8"), 11043, 1955, "regular", "east"),
            new S("100933", "Denver Branch", "CO", "Denver-Aurora-Lakewood", new BigDecimal("104.9"), 8410, 1364, "occasional", "west"),
            new S("100571", "Elkhart Branch", "IN", null, null, 5127, 903, "occasional", "north"),
            new S("100959", "Dallas Branch", "TX", "Dallas-Fort Worth-Arlington", new BigDecimal("99.6"), 21380, 2790, "regular", "south"));

    /** {@code code, name, segment, tier, agreedDiscountPct, typicalQty, profile, slaDays, note} */
    private record C(String code, String name, String segment, String tier, BigDecimal discount, int qty,
            String profile, int slaDays) {
    }

    static final List<C> CUSTOMERS = List.of(
            new C("c-0", "Walk-in / no account", "walk-in", "C", BigDecimal.ZERO, 4, "value", 14),
            new C("c-1", "Halloran Mechanical", "contractor", "A", new BigDecimal("8.5"), 240, "repeat", 5),
            new C("c-2", "Ridgeline Plumbing Co.", "contractor", "B", new BigDecimal("5"), 60, "repeat", 7),
            new C("c-3", "Fairmont Health System", "institutional", "A", new BigDecimal("11"), 120, "enterprise", 2),
            new C("c-4", "Delta Ridge Industrial", "industrial", "A", new BigDecimal("9.5"), 500, "value", 21),
            new C("c-5", "Grayson HVAC Services", "contractor", "C", new BigDecimal("2"), 18, "urgent", 1),
            new C("c-6", "Westport Unified Schools", "institutional", "B", new BigDecimal("7"), 90, "enterprise", 7),
            new C("c-7", "Sable Creek Refining", "industrial", "B", new BigDecimal("6"), 300, "repeat", 5));

    private static final Map<String, CommodityTrend> COMMODITY_TREND = Map.of(
            "copper", new CommodityTrend(6.4, "Copper up on the index"),
            "brass", new CommodityTrend(3.1, "Brass following copper"),
            "steel", new CommodityTrend(-2.2, "Steel easing"),
            "pvc", new CommodityTrend(0.8, "Resin flat"),
            "pex", new CommodityTrend(1.6, "Resin slightly firmer"),
            "iron", new CommodityTrend(-1.1, "Iron soft"),
            "equipment", new CommodityTrend(2.4, "Equipment list prices rising"),
            "none", new CommodityTrend(0, "No commodity exposure"));

    /** US regions: {@code label} and {@code short} are identical, per {@code seed/countries.json}. */
    private static final Map<String, RegionRef> REGIONS = Map.of(
            "west", new RegionRef("west", "West", "West"),
            "north", new RegionRef("north", "North", "North"),
            "south", new RegionRef("south", "South", "South"),
            "east", new RegionRef("east", "East", "East"));

    private static ProductRef toProductRef(P p) {
        return new ProductRef(p.item(), p.desc(), p.shortName(), p.category(), p.subcategory(), p.commodity(),
                p.unit(), p.hasSales(), p.defaultStore());
    }

    private static StoreRef toStoreRef(S s) {
        return new StoreRef(s.code(), "cn-" + s.code(), s.legalName(), "US", s.state(), s.msa(), s.rpp(), s.txns(),
                s.itemCount(), s.segment(), s.region());
    }

    private static CustomerRef toCustomerRef(C c) {
        return new CustomerRef(c.code(), c.name(), c.segment(), c.tier(), c.discount(), c.qty(), c.profile(),
                c.slaDays(), null);
    }

    /** {@code tenantsSellingItem}: the default branch always sells it; else a bit of the item's hash. */
    private static boolean sellsRule(String itemNumber, String storeCode) {
        P product = PRODUCTS.stream().filter(p -> p.item().equals(itemNumber)).findFirst().orElse(null);
        if (product == null || !product.hasSales()) {
            return false;
        }
        if (storeCode.equals(product.defaultStore())) {
            return true;
        }
        long seed = Seeded.hashString(itemNumber);
        for (int i = 0; i < STORES.size(); i++) {
            if (STORES.get(i).code().equals(storeCode)) {
                return (seed >> i) % 3 != 0;
            }
        }
        return false;
    }

    @Override
    public Optional<ProductRef> findProduct(String itemNumber) {
        return PRODUCTS.stream().filter(p -> p.item().equals(itemNumber)).map(FixtureCatalog::toProductRef).findFirst();
    }

    @Override
    public List<ProductRef> sellableProducts() {
        return PRODUCTS.stream().filter(P::hasSales).map(FixtureCatalog::toProductRef).toList();
    }

    @Override
    public Optional<StoreRef> findStore(String storeCode) {
        return STORES.stream().filter(s -> s.code().equals(storeCode)).map(FixtureCatalog::toStoreRef).findFirst();
    }

    @Override
    public List<StoreRef> allStores() {
        return STORES.stream().map(FixtureCatalog::toStoreRef).toList();
    }

    @Override
    public List<String> sellerCodesOf(String itemNumber) {
        return STORES.stream().map(S::code).filter(code -> sellsRule(itemNumber, code)).toList();
    }

    @Override
    public boolean sells(String itemNumber, String storeCode) {
        return sellsRule(itemNumber, storeCode);
    }

    @Override
    public Optional<CustomerRef> findCustomer(String code) {
        return CUSTOMERS.stream().filter(c -> c.code().equals(code)).map(FixtureCatalog::toCustomerRef).findFirst();
    }

    @Override
    public List<CustomerRef> allCustomers() {
        return CUSTOMERS.stream().map(FixtureCatalog::toCustomerRef).toList();
    }

    @Override
    public Optional<RegionRef> regionOf(String regionKey) {
        return Optional.ofNullable(REGIONS.get(regionKey));
    }

    @Override
    public List<RegionRef> allRegions() {
        return new LinkedHashMap<>(REGIONS).values().stream().toList();
    }

    @Override
    public String tenantCountry() {
        return "US";
    }

    @Override
    public CommodityTrend commodityTrend(String commodityKey) {
        return COMMODITY_TREND.getOrDefault(commodityKey, COMMODITY_TREND.get("none"));
    }
}

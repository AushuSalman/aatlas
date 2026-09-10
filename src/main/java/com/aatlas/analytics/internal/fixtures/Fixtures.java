package com.aatlas.analytics.internal.fixtures;

import com.aatlas.common.seed.Seeded;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The static fixture set the procurement ledger and the Buying insights analytics are built
 * on: products, US branches, suppliers, and freight lanes.
 *
 * <p><b>Why this module reads {@code seed/*.json} directly rather than {@code catalog}'s or
 * {@code suppliers}' tables:</b> {@code catalog} exposes no public read API yet (only
 * {@code CatalogSeeding}, for writing a tenant's copy), and reaching into
 * {@code catalog.internal}/{@code suppliers.internal} would fail {@code ModularityTests}
 * regardless. The frontend's own procurement engine (`platform/procurement.ts`) is built the
 * same way: it imports the static fixtures ({@code PRODUCTS}, {@code TENANTS},
 * {@code SUPPLIERS}) directly rather than a tenant's live catalogue, because the ledger is
 * fixture-derived, seeded arithmetic - the same 26-month ledger for every tenant that connects
 * the sample data source, exactly like {@code suppliers.json}'s eight-supplier panel. Reading
 * the seed files here is therefore a faithful port, not a shortcut: {@code products.json} and
 * {@code stores.json}'s {@code US} array are written in PRODUCTS/TENANTS_US order already (see
 * {@code API-BRIEF.md}), and {@code suppliers.json}'s {@code record} objects are the frontend's
 * {@code SupplierRecord} verbatim.
 *
 * <p>TODO(merge): if {@code catalog} grows a public read API (a {@code CatalogReader}
 * interface, say) before this lands, retarget {@link #sellableProducts()} /
 * {@link #stores()} onto a tenant's actual rows instead of the shared fixture - today every
 * tenant that connects the sample source gets an identical catalogue, so the two coincide.
 */
public final class Fixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final List<ProductFixture> PRODUCTS = load("products.json", new TypeReference<List<ProductFixture>>() {
    });
    public static final List<ProductFixture> SELLABLE_PRODUCTS =
            PRODUCTS.stream().filter(ProductFixture::hasSales).toList();
    public static final List<StoreFixture> TENANTS = loadStoresUs();
    public static final List<SupplierFixture> SUPPLIERS = loadSuppliers();
    private static final LogisticsFile LOGISTICS = load("logistics.json", new TypeReference<LogisticsFile>() {
    });
    private static final RegionFixture FALLBACK_REGION = LOGISTICS.regions().get(4); // Midwest
    private static final OriginFixture FALLBACK_ORIGIN =
            new OriginFixture("east", "ocean", "Savannah, GA", 7.5, 30, 5.0, "MFN rate, unlisted origin");

    private Fixtures() {
    }

    private static <T> T load(String fileName, TypeReference<T> type) {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource("classpath:seed/" + fileName);
            try (InputStream in = resource.getInputStream()) {
                return JSON.readValue(in, type);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read seed/" + fileName, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoresFile(@JsonProperty("US") List<StoreFixture> us) {
    }

    private static List<StoreFixture> loadStoresUs() {
        return load("stores.json", new TypeReference<StoresFile>() {
        }).us();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SupplierEntry(@JsonProperty("record") SupplierFixture record_) {
    }

    private static List<SupplierFixture> loadSuppliers() {
        List<SupplierEntry> entries = load("suppliers.json", new TypeReference<List<SupplierEntry>>() {
        });
        return entries.stream().map(SupplierEntry::record_).toList();
    }

    // -- Lookups --------------------------------------------------------------

    public static ProductFixture findProduct(String itemNumber) {
        return PRODUCTS.stream().filter(p -> p.itemNumber().equals(itemNumber)).findFirst().orElse(null);
    }

    public static StoreFixture findStore(String storeId) {
        return TENANTS.stream().filter(t -> t.storeId().equals(storeId)).findFirst().orElse(null);
    }

    public static SupplierFixture findSupplier(String id) {
        return SUPPLIERS.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * Branch names read as network nodes: "Dallas — TX". Mirrors {@code data.ts}'s
     * {@code storeName} - which reads {@code msa_name} (not {@code legal_name}).
     */
    public static String storeName(String storeId) {
        StoreFixture t = findStore(storeId);
        if (t == null) {
            return storeId;
        }
        String msa = t.msaName() == null ? "Branch" : t.msaName();
        return msa.split("-")[0] + " — " + t.state();
    }

    /**
     * Which branches have sold a given item. Ported from {@code mock/catalog.ts}'s
     * {@code tenantsSellingItem}: deterministic from the item number, every product with
     * sales history sold by at least three branches, and the item's own default branch
     * always included.
     */
    public static List<String> tenantsSellingItem(String itemNumber) {
        ProductFixture product = findProduct(itemNumber);
        if (product == null || !product.hasSales()) {
            return List.of();
        }
        long seed = Seeded.hashString(product.itemNumber());
        List<String> sellers = new ArrayList<>();
        for (int i = 0; i < TENANTS.size(); i++) {
            StoreFixture t = TENANTS.get(i);
            if (t.storeId().equals(product.defaultTenant()) || (seed >> i) % 3 != 0) {
                sellers.add(t.storeId());
            }
        }
        return sellers;
    }

    public static boolean priceable(String itemNumber, String storeId) {
        ProductFixture product = findProduct(itemNumber);
        if (product == null || !product.hasSales()) {
            return false;
        }
        return storeId == null || storeId.isBlank() || tenantsSellingItem(itemNumber).contains(storeId);
    }

    public static RegionFixture regionForState(String state) {
        if (state == null) {
            return FALLBACK_REGION;
        }
        for (RegionFixture r : LOGISTICS.regions()) {
            if (r.states().contains(state)) {
                return r;
            }
        }
        return FALLBACK_REGION;
    }

    public static RegionFixture regionForStore(String storeId) {
        StoreFixture t = findStore(storeId);
        return regionForState(t == null ? null : t.state());
    }

    /** Ex-works plus this lane's freight and duty, landed at {@code region}. Ports {@code logistics.ts}'s {@code laneFor}. */
    public static Lane laneFor(String originCountry, RegionFixture region) {
        OriginFixture o = LOGISTICS.origins().getOrDefault(originCountry, FALLBACK_ORIGIN);
        double freightPct = Math.round((o.inboundPct() + region.inlandPct().get(o.entry())) * 10) / 10.0;
        int transitDays = o.inboundDays() + region.inlandDays().get(o.entry());
        return new Lane(originCountry, region.key(), region.label(), freightPct, o.dutyPct(), o.dutyNote(), transitDays);
    }

    /**
     * Who a branch buys a given item from today. Ported from {@code data.ts}'s
     * {@code currentSupplierFor}: not a flat draw - roughly seven lines in ten sit in the
     * cheaper half of the panel (ranked by price index), the rest in the dearer half.
     */
    public static SupplierFixture currentSupplierFor(String itemNumber) {
        List<SupplierFixture> ranked = SUPPLIERS.stream()
                .sorted((a, b) -> Double.compare(a.priceIndex(), b.priceIndex()))
                .toList();
        int half = (int) Math.ceil(ranked.size() / 2.0);
        List<SupplierFixture> pool = Seeded.rand(itemNumber, "inc-tier") > 0.3
                ? ranked.subList(0, half)
                : ranked.subList(half, ranked.size());
        return Seeded.pick(itemNumber, "current-sup", pool);
    }
}

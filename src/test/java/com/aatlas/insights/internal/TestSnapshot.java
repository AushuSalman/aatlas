package com.aatlas.insights.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;

/**
 * Builds a {@link CatalogSnapshot} straight from the shared seed files under
 * {@code src/main/resources/seed}, for golden-file tests that need no database - the same
 * pattern {@code suppliers.SupplierEngineGoldenTest} uses for {@code seed/suppliers.json}.
 *
 * <p>This is a test-only mirror of what {@link CatalogSnapshotReader} reads from Postgres
 * for a freshly seeded tenant: the same seed files, the same field mapping catalog's
 * {@code SampleCatalogueSeeder} uses to write them, and the same seller rule (a
 * self-contained copy of catalog's {@code SellersRule} - not a shared dependency, since
 * this module cannot import {@code catalog.internal}; both are pinned against the same
 * {@code tenantsSellingItem} in {@code mock/catalog.ts} and must agree).
 */
final class TestSnapshot {

    private TestSnapshot() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static CatalogSnapshot us() {
        List<SeedProduct> seedProducts = read("products.json", new TypeReference<List<SeedProduct>>() {
        });
        Map<String, List<SeedStore>> seedStores = read("stores.json", new TypeReference<Map<String, List<SeedStore>>>() {
        });
        List<SeedSupplier> seedSuppliers = read("suppliers.json", new TypeReference<List<SeedSupplier>>() {
        });
        Map<String, SeedCommodity> seedCommodities = read("commodities.json", new TypeReference<Map<String, SeedCommodity>>() {
        });
        List<SeedCountry> countries = read("countries.json", new TypeReference<List<SeedCountry>>() {
        });
        SeedLogistics logistics = read("logistics.json", new TypeReference<SeedLogistics>() {
        });

        List<String> storeCodesInSeedOrder = seedStores.get("US").stream().map(SeedStore::storeId).toList();

        Map<String, StoreRef> stores = new LinkedHashMap<>();
        for (SeedStore s : seedStores.get("US")) {
            stores.put(s.storeId(), new StoreRef(
                    UUID.randomUUID(), s.storeId(), s.legalName(), "US", s.state(), s.msaName(), s.rpp(), s.txns(),
                    s.segment(), s.regionKey()));
        }

        Map<String, Set<String>> sellers = new LinkedHashMap<>();
        for (SeedProduct p : seedProducts) {
            if (!Boolean.FALSE.equals(p.hasSales())) {
                sellers.put(p.itemNumber(), new LinkedHashSet<>(sellers(p.itemNumber(), p.defaultTenant(), storeCodesInSeedOrder)));
            }
        }

        List<ProductRef> products = new ArrayList<>();
        for (SeedProduct p : seedProducts) {
            products.add(new ProductRef(p.itemNumber(), p.description(),
                    p.shortName() == null ? p.description() : p.shortName(),
                    p.category() == null ? "Plumbing" : p.category(),
                    p.subcategory() == null ? "Other" : p.subcategory(),
                    p.commodity() == null ? "none" : p.commodity(),
                    p.unit() == null ? "each" : p.unit(),
                    !Boolean.FALSE.equals(p.hasSales()), p.defaultTenant()));
        }

        List<SupplierRef> suppliers = new ArrayList<>();
        for (SeedSupplier s : seedSuppliers) {
            SeedSupplierRecord r = s.record();
            suppliers.add(new SupplierRef(r.id(), r.name(), r.country(), r.leadTimeDays(), r.otifPct(), r.priceIndex()));
        }

        Map<String, CommodityRef> commodities = new LinkedHashMap<>();
        seedCommodities.forEach((k, v) -> commodities.put(k, new CommodityRef(v.pct90(), v.label())));

        SeedCountry us = countries.stream().filter(c -> "US".equals(c.code())).findFirst().orElseThrow();
        Map<String, String> subdivisionNames = new LinkedHashMap<>();
        List<MarketRegionRef> marketRegions = new ArrayList<>();
        for (SeedRegion r : us.regions()) {
            List<String> codes = new ArrayList<>();
            for (SeedSubdivision sub : r.subdivisions()) {
                codes.add(sub.code());
                subdivisionNames.put(sub.code(), sub.name());
            }
            marketRegions.add(new MarketRegionRef(r.key(), r.shortLabel(), r.label(), List.copyOf(codes)));
        }

        List<LogisticsLaneRef> lanes = logistics.regions().stream()
                .map(l -> new LogisticsLaneRef(l.key(), l.label(), l.states(), l.inlandPct(), l.inlandDays()))
                .toList();
        Map<String, LogisticsOriginRef> origins = new LinkedHashMap<>();
        logistics.origins().forEach((k, v) -> origins.put(k,
                new LogisticsOriginRef(v.entry(), v.mode(), v.gateway(), v.inboundPct(), v.inboundDays(), v.dutyPct())));

        return new CatalogSnapshot(products, stores, sellers, suppliers, commodities, marketRegions,
                subdivisionNames, lanes, origins);
    }

    /** A self-contained copy of catalog's {@code SellersRule.sellers} - see the class javadoc. */
    private static List<String> sellers(String itemNumber, String defaultStoreCode, List<String> storeCodesInSeedOrder) {
        int seed = hashString(itemNumber);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < storeCodesInSeedOrder.size(); i++) {
            String code = storeCodesInSeedOrder.get(i);
            if (code.equals(defaultStoreCode) || (seed >> i) % 3 != 0) {
                out.add(code);
            }
        }
        return out;
    }

    private static int hashString(String value) {
        int h = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x01000193;
        }
        return Math.abs(h);
    }

    private static <T> T read(String file, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource("seed/" + file).getInputStream()) {
            return JSON.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read seed/" + file, ex);
        }
    }

    // ---- seed file shapes (only the fields this module reads) -----------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedProduct(
            String itemNumber, String description, String defaultTenant, Boolean hasSales,
            String shortName, String category, String subcategory, String commodity, String unit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedStore(
            @JsonProperty("store_id") String storeId,
            @JsonProperty("legal_name") String legalName,
            String state,
            @JsonProperty("msa_name") String msaName,
            BigDecimal rpp,
            Integer txns,
            String segment,
            String regionKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedSupplier(SeedSupplierRecord record) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedSupplierRecord(
            String id, String name, String country, int leadTimeDays, double otifPct, double priceIndex) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedCommodity(BigDecimal pct90, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedCountry(String code, List<SeedRegion> regions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedRegion(String key, String label, @JsonProperty("short") String shortLabel, List<SeedSubdivision> subdivisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedSubdivision(String code, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedLogistics(List<SeedLane> regions, Map<String, SeedOrigin> origins) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedLane(String key, String label, List<String> states, Map<String, BigDecimal> inlandPct, Map<String, Integer> inlandDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedOrigin(String entry, String mode, String gateway, BigDecimal inboundPct, int inboundDays, BigDecimal dutyPct) {
    }
}

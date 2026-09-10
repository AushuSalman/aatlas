package com.aatlas.bulk.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The static reference data {@link PricingEngine} and the bulk strategies need:
 * products, US branches, and the supplier panel.
 *
 * <p><b>Stand-in.</b> {@code catalog}'s only public type is {@code CatalogSeeding} (for
 * {@code ingest} to copy the seed into a tenant's own tables); it exposes no reader, so
 * there is nothing to depend on for a product or branch lookup. Reading
 * {@code seed/products.json} and {@code seed/stores.json} directly - the same files
 * {@code catalog} loads, and the same static lists {@code mock/catalog.ts}'s
 * {@code PRODUCTS}/{@code TENANTS} export - sidesteps the missing reader entirely rather
 * than guessing at one, at the cost of not reflecting a tenant's own catalogue if it ever
 * diverges from the sample (it cannot today: the only source is "sample").
 * {@code TODO(merge): replace with the catalog module's public reader, if/when it gets
 * one, for tenant-accurate data.} Suppliers are read the same way from
 * {@code seed/suppliers.json}, standing in for the {@code suppliers} module's internal
 * panel for the same reason.
 */
@Component
public class BulkSeedCatalog {

    private final ObjectMapper json;

    private volatile List<SeedProduct> products;
    private volatile List<SeedStore> stores;
    private volatile List<SeedSupplier> suppliers;

    public BulkSeedCatalog(ObjectMapper json) {
        this.json = json;
    }

    public List<SeedProduct> products() {
        if (products == null) {
            products = read("products.json", new TypeReference<>() {});
        }
        return products;
    }

    public Optional<SeedProduct> product(String itemNumber) {
        return products().stream().filter(p -> p.itemNumber().equals(itemNumber)).findFirst();
    }

    /** The US branch list, in seed order - the order {@code tenantsSellingItem} indexes by. */
    public List<SeedStore> stores() {
        if (stores == null) {
            Map<String, List<SeedStore>> byCountry = read("stores.json", new TypeReference<>() {});
            stores = List.copyOf(byCountry.getOrDefault("US", List.of()));
        }
        return stores;
    }

    public Optional<SeedStore> store(String storeId) {
        return stores().stream().filter(s -> s.storeId().equals(storeId)).findFirst();
    }

    public List<SeedStore> storesInRegion(String regionKey) {
        return stores().stream().filter(s -> regionKey.equals(s.regionKey())).toList();
    }

    public List<SeedSupplier> suppliers() {
        if (suppliers == null) {
            suppliers = read("suppliers.json", new TypeReference<>() {});
        }
        return suppliers;
    }

    private <T> T read(String file, TypeReference<T> type) {
        ClassPathResource resource = new ClassPathResource("seed/" + file);
        try (InputStream in = resource.getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read seed file " + file, ex);
        }
    }

    // ---- the shapes, named as the seed files and the TypeScript spell them --------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedProduct(
            String itemNumber,
            String description,
            String defaultTenant,
            Boolean hasSales,
            String shortName,
            String category,
            String subcategory,
            String commodity,
            String unit) {

        /** The frontend's rule is {@code hasSales !== false}: absent means sellable. */
        public boolean sellable() {
            return hasSales == null || hasSales;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedStore(
            @JsonProperty("store_id") String storeId,
            @JsonProperty("legal_name") String legalName,
            String state,
            @JsonProperty("msa_name") String msaName,
            Double rpp,
            Integer txns,
            String segment,
            String country,
            String regionKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedSupplier(SeedSupplierRecord record, SeedSupplierProfile profile, SeedSupplierTerms terms) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedSupplierRecord(
            String id,
            String name,
            String country,
            int leadTimeDays,
            double otifPct,
            double priceIndex,
            int moq) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedSupplierProfile(String id, double defectPct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedSupplierTerms(
            int creditDays,
            String termsLabel,
            double earlyPayDiscountPct,
            int earlyPayDays,
            double latePenaltyPctPerWeek,
            double latePenaltyCapPct,
            int warrantyMonths,
            int quoteValidityDays,
            String incoterm,
            double invoiceAccuracyPct,
            int capacityUnitsMonth) {
    }

    /** Keeps insertion order when a caller needs a lookup map, e.g. by supplier id. */
    public static <T> Map<String, T> indexBy(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, T> out = new LinkedHashMap<>();
        values.forEach(v -> out.put(key.apply(v), v));
        return out;
    }
}

package com.aatlas.assistant.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Just enough static catalogue for the assistant's intent matchers: products, US
 * branches, and the four market regions.
 *
 * <p><b>Stand-in.</b> {@code catalog} exposes no reader (see the note on
 * {@code com.aatlas.bulk.internal.BulkSeedCatalog}, which reads the same two seed files
 * for the same reason). This module reads its own copy rather than depending on
 * {@code bulk}'s internal loader - the assistant is a router over other engines' facts,
 * not a bulk client - at the cost of the small duplication.
 * {@code TODO(merge): replace with the catalog module's public reader, if/when it gets
 * one.}
 */
@Component
public class AssistantCatalog {

    private final ObjectMapper json;

    private volatile List<SeedProduct> products;
    private volatile List<SeedStore> stores;

    public AssistantCatalog(ObjectMapper json) {
        this.json = json;
    }

    public List<SeedProduct> products() {
        if (products == null) {
            products = read("products.json", new TypeReference<>() {});
        }
        return products;
    }

    /** Sellable products only: {@code SELLABLE_PRODUCTS} in {@code platform/data.ts}. */
    public List<SeedProduct> sellableProducts() {
        return products().stream().filter(SeedProduct::sellable).toList();
    }

    public List<SeedStore> stores() {
        if (stores == null) {
            Map<String, List<SeedStore>> byCountry = read("stores.json", new TypeReference<>() {});
            stores = List.copyOf(byCountry.getOrDefault("US", List.of()));
        }
        return stores;
    }

    private <T> T read(String file, TypeReference<T> type) {
        ClassPathResource resource = new ClassPathResource("seed/" + file);
        try (InputStream in = resource.getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read seed file " + file, ex);
        }
    }

    /** "Dallas" from "Dallas-Fort Worth-Arlington". Port of {@code catalog.ts}'s {@code storeCity}. */
    public String storeCity(String storeId) {
        return stores().stream().filter(s -> s.storeId().equals(storeId)).findFirst()
                .map(s -> {
                    String source = s.msaName() != null ? s.msaName() : s.legalName();
                    return source == null ? storeId : source.split("-")[0].replace(" Branch", "").trim();
                })
                .orElse(storeId);
    }

    public String storeLabel(String storeId) {
        return storeCity(storeId) + " #" + storeId;
    }

    /** US market regions, ported from {@code platform/locale.ts}'s US entry. */
    public static final List<MarketRegion> MARKET_REGIONS = List.of(
            new MarketRegion("west", "West"),
            new MarketRegion("north", "North"),
            new MarketRegion("south", "South"),
            new MarketRegion("east", "East"));

    public record MarketRegion(String key, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedProduct(String itemNumber, String description, Boolean hasSales, String shortName,
            String category, String commodity) {

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
            String regionKey) {
    }

    /** Case-insensitive lookup by item number. */
    public java.util.Optional<SeedProduct> product(String itemNumber) {
        return products().stream()
                .filter(p -> p.itemNumber().equalsIgnoreCase(itemNumber))
                .findFirst();
    }

    public java.util.Optional<SeedStore> store(String storeId) {
        return stores().stream().filter(s -> s.storeId().equals(storeId)).findFirst();
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}

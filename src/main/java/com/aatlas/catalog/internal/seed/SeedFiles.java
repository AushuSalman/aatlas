package com.aatlas.catalog.internal.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The seed files under {@code src/main/resources/seed}, read once and typed.
 *
 * <p>They were generated from the frontend fixtures with the TypeScript field names kept
 * verbatim, so the records here spell the keys the same way - including the snake_case
 * ones on a store, which the frontend's {@code StoreItem} reads as they are. Unknown keys
 * are ignored on purpose: {@code products.json} carries the picker's {@code value} and
 * {@code label} duplicates, and a regenerated file may gain a field before this code
 * needs it.
 *
 * <p>Read lazily and cached: the reference loader wants countries, logistics and
 * commodities at startup; the sample provisioner wants the rest only when a tenant asks.
 */
@Component
public class SeedFiles {

    private final ObjectMapper json;

    private volatile List<SeedProduct> products;
    private volatile Map<String, List<SeedStore>> stores;
    private volatile List<SeedCustomer> customers;
    private volatile List<SeedCountry> countries;
    private volatile SeedLogistics logistics;
    private volatile Map<String, SeedCommodity> commodities;

    public SeedFiles(ObjectMapper json) {
        this.json = json;
    }

    public List<SeedProduct> products() {
        if (products == null) {
            products = read("products.json", new TypeReference<>() {});
        }
        return products;
    }

    /** Keyed by country code, {@code US} and {@code UK}, in the seed's order. */
    public Map<String, List<SeedStore>> stores() {
        if (stores == null) {
            stores = read("stores.json", new TypeReference<>() {});
        }
        return stores;
    }

    public List<SeedCustomer> customers() {
        if (customers == null) {
            customers = read("customers.json", new TypeReference<>() {});
        }
        return customers;
    }

    public List<SeedCountry> countries() {
        if (countries == null) {
            countries = read("countries.json", new TypeReference<>() {});
        }
        return countries;
    }

    public SeedLogistics logistics() {
        if (logistics == null) {
            logistics = read("logistics.json", new TypeReference<>() {});
        }
        return logistics;
    }

    /** Keyed by commodity, in the seed's order. */
    public Map<String, SeedCommodity> commodities() {
        if (commodities == null) {
            commodities = read("commodities.json", new TypeReference<>() {});
        }
        return commodities;
    }

    private <T> T read(String file, TypeReference<T> type) {
        ClassPathResource resource = new ClassPathResource("seed/" + file);
        try (InputStream in = resource.getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read seed file " + file, ex);
        }
    }

    // ---- the shapes, named as the TypeScript types name them --------------------------

    /** A row of {@code products.json}; the frontend's {@code ProductOption} plus its meta. */
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

    /** A row of {@code stores.json}; the frontend's {@code StoreItem} plus country and map. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedStore(
            @JsonProperty("store_id") String storeId,
            @JsonProperty("company_number") String companyNumber,
            @JsonProperty("legal_name") String legalName,
            String state,
            @JsonProperty("msa_name") String msaName,
            BigDecimal rpp,
            Integer txns,
            @JsonProperty("item_count") Integer itemCount,
            String segment,
            String country,
            String regionKey,
            SeedMap map) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedMap(Integer x, Integer y, String anchor) {
    }

    /** A row of {@code customers.json}; the frontend's {@code CustomerRecord}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedCustomer(
            String id,
            String name,
            String segment,
            String tier,
            BigDecimal agreedDiscountPct,
            Integer typicalQty,
            String note,
            String profile,
            Integer slaDays) {
    }

    /** A row of {@code countries.json}; the frontend's {@code CountryInfo}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedCountry(String code, String name, String currency, List<SeedRegion> regions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedRegion(
            String key,
            String label,
            @JsonProperty("short") String shortLabel,
            List<SeedSubdivision> subdivisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedSubdivision(String code, String name) {
    }

    /** {@code logistics.json}: the frontend's {@code REGIONS} and {@code ORIGINS}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedLogistics(List<SeedLane> regions, Map<String, SeedOrigin> origins) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedLane(
            String key,
            String label,
            List<String> states,
            Map<String, BigDecimal> inlandPct,
            Map<String, Integer> inlandDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedOrigin(
            String entry,
            String mode,
            String gateway,
            BigDecimal inboundPct,
            Integer inboundDays,
            BigDecimal dutyPct,
            String dutyNote) {
    }

    /** A value of {@code commodities.json}; the frontend's {@code COMMODITY_TREND} entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeedCommodity(BigDecimal pct90, String label) {
    }
}

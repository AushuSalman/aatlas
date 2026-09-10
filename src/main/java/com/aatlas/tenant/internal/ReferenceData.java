package com.aatlas.tenant.internal;

import com.aatlas.tenant.CountryCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The two reference files this module serves and validates against, read once at startup.
 *
 * <p>{@code seed/countries.json} is served byte for byte by {@code GET /reference/countries}
 * - it is generated from the frontend's {@code COUNTRIES} and the frontend renders it
 * verbatim, so re-serialising it would only risk reordering a key. The parsed form here is
 * for the settings endpoint, which needs each country's nouns and default currency.
 *
 * <p>{@code seed/currencies.json} supplies the static half of a currency (symbol, name,
 * locale, decimal places); the rate half comes from {@code fx_rate} so a nightly job can
 * move it without a deploy.
 */
@Component
class ReferenceData {

    /** What the settings endpoint needs to know about a country. */
    record Country(
            CountryCode code,
            String name,
            String currency,
            String subdivisionNoun,
            String subdivisionNounPlural,
            String regionNoun) {
    }

    /** The static half of a currency; {@code perUsd} is the seed's rate for the FX fallback. */
    record Currency(String code, String symbol, String name, String locale, BigDecimal perUsd, int dp) {
    }

    private final byte[] countriesJson;
    private final String countriesEtag;
    private final Map<CountryCode, Country> countries;
    private final LocalDate currenciesAsOf;
    private final Map<String, Currency> currencies;

    ReferenceData(ObjectMapper json) {
        this.countriesJson = read("seed/countries.json");
        this.countriesEtag = "\"" + sha256Hex(countriesJson) + "\"";
        try {
            Map<CountryCode, Country> byCode = new LinkedHashMap<>();
            for (JsonNode node : json.readTree(countriesJson)) {
                Country country = new Country(
                        CountryCode.from(node.get("code").asText()),
                        node.get("name").asText(),
                        node.get("currency").asText(),
                        node.get("subdivisionNoun").asText(),
                        node.get("subdivisionNounPlural").asText(),
                        node.get("regionNoun").asText());
                byCode.put(country.code(), country);
            }
            // Collections.unmodifiableMap, not Map.copyOf: the immutable maps Map.copyOf
            // returns do not preserve iteration order (they salt their hashing per JVM run
            // for flood resistance), and the currency picker's order is the seed's order.
            this.countries = Collections.unmodifiableMap(byCode);

            JsonNode root = json.readTree(read("seed/currencies.json"));
            this.currenciesAsOf = LocalDate.parse(root.get("asOf").asText());
            Map<String, Currency> byCurrency = new LinkedHashMap<>();
            for (JsonNode node : root.get("currencies")) {
                Currency currency = new Currency(
                        node.get("code").asText(),
                        node.get("symbol").asText(),
                        node.get("name").asText(),
                        node.get("locale").asText(),
                        node.get("perUsd").decimalValue(),
                        node.get("dp").asInt());
                byCurrency.put(currency.code(), currency);
            }
            this.currencies = Collections.unmodifiableMap(byCurrency);
        } catch (IOException ex) {
            throw new UncheckedIOException("The reference seed files are unreadable", ex);
        }

        // Every CountryCode must be described, or the settings endpoint would answer
        // "Region" for a country it knows nothing about.
        for (CountryCode code : CountryCode.values()) {
            if (!countries.containsKey(code)) {
                throw new IllegalStateException("seed/countries.json has no entry for " + code);
            }
        }
    }

    byte[] countriesJson() {
        return countriesJson.clone();
    }

    String countriesEtag() {
        return countriesEtag;
    }

    Country country(CountryCode code) {
        return countries.get(code);
    }

    LocalDate currenciesAsOf() {
        return currenciesAsOf;
    }

    /** In seed order, which is the order the currency picker shows them. */
    List<Currency> currencies() {
        return List.copyOf(currencies.values());
    }

    Set<String> currencyCodes() {
        return currencies.keySet();
    }

    boolean isCurrency(String code) {
        return code != null && currencies.containsKey(code.strip().toUpperCase(Locale.ROOT));
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(path + " is missing or unreadable", ex);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", ex);
        }
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

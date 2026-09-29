package com.aatlas.competition.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * One source of live competitor prices: a keyword search that returns priced listings.
 *
 * <p>A provider with no key configured reports {@link #available()} false and is never
 * called; a provider that fails throws {@link ProviderFailed}, which the caller reports
 * against that provider alone - one bad key never hides another provider's answer.
 */
interface ShoppingProvider {

    /** Stable key, also the {@code competitor_prices.source} of what it finds. */
    String key();

    /** "Google Shopping (SerpApi)" - what a screen calls it. */
    String label();

    /** What it covers and what it costs, in one line, for the settings screen. */
    String plan();

    boolean available();

    List<Listing> search(String query, Market market, int max);

    /**
     * A priced listing as the provider returned it.
     *
     * @param merchant who sells it: the store on Google Shopping, the seller on eBay, Amazon on Rainforest
     */
    record Listing(String provider, String title, BigDecimal price, String currency, String merchant, String url) {
    }

    /** Where to search: the tenant's country decides the storefront and the currency. */
    record Market(String country, String currency) {

        static Market of(String country) {
            return "UK".equalsIgnoreCase(country) ? new Market("UK", "GBP") : new Market("US", "USD");
        }

        boolean uk() {
            return "UK".equals(country);
        }
    }

    class ProviderFailed extends RuntimeException {
        ProviderFailed(String provider, Throwable cause) {
            super(provider + " search failed: " + cause.getMessage(), cause);
        }

        ProviderFailed(String message) {
            super(message);
        }
    }

    /** The same timeouts every provider uses: a slow provider must not hold a bulk refresh. */
    static ClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** A price as a provider sends it - a JSON number or a "$1,234.56" string - or null. */
    static BigDecimal money(com.fasterxml.jackson.databind.JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        if (raw.isNumber()) {
            return raw.decimalValue();
        }
        String s = raw.asText().replaceAll("[^0-9.]", "");
        if (s.isEmpty() || s.chars().filter(c -> c == '.').count() > 1) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

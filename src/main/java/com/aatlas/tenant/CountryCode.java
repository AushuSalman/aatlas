package com.aatlas.tenant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Where a tenant trades.
 *
 * <p>Deliberately a closed set of two rather than an ISO-3166 lookup: the country picks
 * the map, the subdivision vocabulary (states vs regions) and the trading currency, and
 * every one of those is a fixture that has to exist before a country can be offered.
 * Adding a third is a data exercise, not a validation change.
 *
 * <p>Mirrors {@code CountryCode} in the frontend's {@code src/lib/platform/locale.ts}.
 * Note {@code UK} rather than the ISO {@code GB} - the wire contract follows the client
 * that already shipped.
 */
public enum CountryCode {

    US("USD"),
    UK("GBP");

    private final String tradingCurrency;

    CountryCode(String tradingCurrency) {
        this.tradingCurrency = tradingCurrency;
    }

    /** ISO-4217 code this country's tenants price in. */
    public String tradingCurrency() {
        return tradingCurrency;
    }

    @JsonValue
    public String wireValue() {
        return name();
    }

    /**
     * Accepts either case so a hand-written client is not tripped by {@code "us"}.
     * An unknown value fails here rather than defaulting, because silently trading in
     * the wrong currency is worse than a 400.
     */
    @JsonCreator
    public static CountryCode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("country is required");
        }
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}

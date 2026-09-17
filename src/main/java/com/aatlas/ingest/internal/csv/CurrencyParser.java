package com.aatlas.ingest.internal.csv;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a currency cell into a 3-letter code.
 *
 * <p>Exports write {@code USD}, {@code $}, {@code US$}, {@code Dollars}; all of those are one
 * currency. Anything not recognised is empty, and the validator turns that into an error the
 * user can act on rather than a silent assumption about what the figures mean.
 */
public final class CurrencyParser {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z]{3}$");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("$", "USD"), Map.entry("us$", "USD"), Map.entry("usdollar", "USD"),
            Map.entry("dollar", "USD"), Map.entry("dollars", "USD"),
            Map.entry("£", "GBP"), Map.entry("pound", "GBP"), Map.entry("pounds", "GBP"),
            Map.entry("sterling", "GBP"),
            Map.entry("€", "EUR"), Map.entry("euro", "EUR"), Map.entry("euros", "EUR"),
            Map.entry("c$", "CAD"),
            Map.entry("a$", "AUD"),
            Map.entry("rs", "INR"), Map.entry("rupee", "INR"), Map.entry("rupees", "INR"), Map.entry("₹", "INR"),
            Map.entry("peso", "MXN"), Map.entry("pesos", "MXN"),
            Map.entry("rmb", "CNY"), Map.entry("yuan", "CNY"), Map.entry("¥", "CNY"),
            Map.entry("dong", "VND"), Map.entry("₫", "VND"),
            Map.entry("yen", "JPY"));

    private CurrencyParser() {
    }

    /** Upper-cased 3-letter code, or empty when the cell is blank or not recognised. */
    public static Optional<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.strip();
        if (CODE.matcher(text).matches()) {
            return Optional.of(text.toUpperCase(Locale.ROOT));
        }
        String key = text.toLowerCase(Locale.ROOT).replace(" ", "");
        return Optional.ofNullable(ALIASES.get(key));
    }
}

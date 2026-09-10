package com.aatlas.suppliers.internal;

import java.util.Locale;

/**
 * The currency a supplier in a country would naturally quote in. A port of
 * {@code currencyForCountry} in the frontend's {@code platform/money.ts}, which is what the
 * Suppliers page shows in its "Currencies" tile.
 */
final class Currencies {

    private Currencies() {
    }

    static String forCountry(String country) {
        String c = country == null ? "" : country.strip().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "usa", "us", "united states" -> "USD";
            case "uk", "united kingdom", "england", "scotland", "wales" -> "GBP";
            case "germany", "france", "italy", "spain", "netherlands", "belgium", "austria", "ireland", "poland" ->
                    "EUR";
            case "canada" -> "CAD";
            case "australia" -> "AUD";
            case "india" -> "INR";
            case "mexico" -> "MXN";
            case "china" -> "CNY";
            case "vietnam" -> "VND";
            case "japan" -> "JPY";
            default -> "USD";
        };
    }
}

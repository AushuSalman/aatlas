package com.aatlas.suppliers.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A supplier's country, in the one spelling the rest of the platform reads it by.
 *
 * <p>This exists because the string is not a label. It is the key to the shipping lane, and
 * through the lane to the inbound freight percentage, the duty rate and the transit days;
 * it also picks the currency the supplier quotes in. {@code LogisticsEngine.laneFor} looks
 * it up with an exact-match {@code getOrDefault}, so "VN" and "Vietnam" are not the same
 * supplier to it - the first misses every row and falls to {@code FALLBACK_ORIGIN}, which
 * is an ocean crossing with a 5% MFN duty and a thirty-day transit. A supplier costed that
 * way is not visibly wrong anywhere; the number simply comes out too high, on every quote,
 * for as long as the row exists.
 *
 * <p>The alias table itself is not new - it was written for the CSV import, where somebody
 * typing a file of their own suppliers obviously writes "GB" or "United Kingdom". It lived
 * as private statics inside {@code SupplierImportValidator}, so a supplier that arrived
 * through the file got it and the same supplier typed into the form or posted to the API
 * did not. Nothing about the problem was ever specific to CSV; it is specific to *writing a
 * supplier*, which is what this class is for.
 *
 * <p>Deliberately normalising rather than restricting. A supplier in France or Japan is a
 * real supplier with no lane on file, and the platform has an answer for that - the
 * fallback origin, which says "MFN rate, unlisted origin" and means it. Rejecting them
 * would be worse than costing them conservatively. What must not happen is a supplier
 * landing on the fallback because of how their country was spelt.
 */
public final class Countries {

    /**
     * Countries with a shipping lane on file - the keys of {@code logistics_origins}.
     *
     * <p>Kept here as a copy rather than read from the table because it answers a question
     * at write time ("will this supplier be costed properly?") that the rate card, a
     * global reference table owned by another module, is not asked at write time. It is
     * small, it changes with a migration, and {@link #hasLane} is the only thing that reads
     * it.
     */
    private static final Set<String> LANE_COUNTRIES =
            Set.of("USA", "UK", "Germany", "China", "India", "Vietnam", "Mexico", "Canada");

    /** "united states", "US" and "USA" are one lane. Anything unlisted is taken as written. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("us", "USA"), Map.entry("usa", "USA"), Map.entry("unitedstates", "USA"),
            Map.entry("unitedstatesofamerica", "USA"), Map.entry("america", "USA"),
            Map.entry("uk", "UK"), Map.entry("gb", "UK"), Map.entry("unitedkingdom", "UK"),
            Map.entry("greatbritain", "UK"), Map.entry("england", "UK"),
            Map.entry("de", "Germany"), Map.entry("germany", "Germany"), Map.entry("deutschland", "Germany"),
            Map.entry("cn", "China"), Map.entry("china", "China"), Map.entry("prc", "China"),
            Map.entry("in", "India"), Map.entry("india", "India"),
            Map.entry("vn", "Vietnam"), Map.entry("vietnam", "Vietnam"), Map.entry("viet", "Vietnam"),
            Map.entry("mx", "Mexico"), Map.entry("mexico", "Mexico"),
            Map.entry("ca", "Canada"), Map.entry("canada", "Canada"));

    private Countries() {
    }

    /**
     * The canonical spelling, or null when nothing was sent.
     *
     * <p>A country with no alias is returned stripped and otherwise untouched: the platform
     * does not have an opinion about how France is spelt, only about the eight it prices
     * lanes for.
     */
    public static String canonical(String raw) {
        String key = normalise(raw);
        if (key.isEmpty()) {
            return null;
        }
        return ALIASES.getOrDefault(key, raw.strip());
    }

    /** {@link #canonical}, with a fallback for the callers that already know it is present. */
    public static String canonicalOr(String raw, String fallback) {
        String canonical = canonical(raw);
        return canonical == null ? fallback : canonical;
    }

    /**
     * Whether this country has a shipping lane on file.
     *
     * <p>False is not an error - it is the warning the CSV import shows and the reason the
     * landed cost carries "MFN rate, unlisted origin" rather than a real rate.
     */
    public static boolean hasLane(String country) {
        return country != null && LANE_COUNTRIES.contains(country);
    }

    /** Letters and digits, lowercased: "United Kingdom", "united-kingdom" and "UnitedKingdom" are one key. */
    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).chars()
                .filter(Character::isLetterOrDigit)
                .forEach(c -> out.append((char) c));
        return out.toString();
    }
}

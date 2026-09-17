package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the value types an export gets wrong most often.
 *
 * <p>Every parser returns empty rather than throwing: a bad cell is a reportable row issue,
 * not an exception, because the point of the import screen is to show the user every problem
 * at once instead of stopping at the first.
 */
public final class ValueParsers {

    /**
     * Which way round an ambiguous {@code 03/04/2026} is read. Chosen once per batch from the
     * tenant's country: UK tenants write day first, US tenants month first. A date impossible
     * under the tenant's order is reported, never silently swapped.
     */
    public enum DateOrder {
        MDY,
        DMY;

        public static DateOrder forCountry(String country) {
            return country != null && country.strip().equalsIgnoreCase("UK") ? DMY : MDY;
        }
    }

    /** {@code YYYY-MM-DD} or {@code YYYY/MM/DD}. */
    private static final Pattern ISO = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");

    /** {@code YYYYMMDD}, the SAP way. */
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");

    /** {@code MM/DD/YYYY} or {@code DD/MM/YYYY}, either separator; the order decides. */
    private static final Pattern SLASHED = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$");

    /** {@code DD-MMM-YYYY}, with a dash, slash or space. */
    private static final Pattern MONTH_NAME =
            Pattern.compile("^(\\d{1,2})[-/ ]([A-Za-z]{3})[a-z]*[-/ ](\\d{4})$");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    /** Currency symbols, thousands separators, percent signs and whitespace: all noise to a number. */
    private static final Pattern NUMBER_NOISE = Pattern.compile("[$£€¥₹,%\\s]");

    /** Accounting's way of writing a negative: (1,234.00). */
    private static final Pattern PARENTHESISED = Pattern.compile("^\\(.*\\)$");

    private ValueParsers() {
    }

    /** US order; kept for the sales path whose messages promise {@code MM/DD/YYYY}. */
    public static Optional<LocalDate> parseDate(String raw) {
        return parseDate(raw, DateOrder.MDY);
    }

    /**
     * Reads a date.
     *
     * <p>Four-digit-year-first is unambiguous. The other form is not - {@code 03/04/2026} is
     * March in the United States and April almost everywhere else - so the tenant's order is
     * applied, and anything impossible under it (a {@code 13} in the month position) is
     * reported rather than silently swapped, because a silent swap would move a transaction
     * by up to eleven months and nobody would see it. A trailing time ({@code T10:30:00} or
     * {@code 10:30}) is dropped: these are day-grained facts.
     */
    public static Optional<LocalDate> parseDate(String raw, DateOrder order) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = stripTime(raw.strip());
        if (text.isEmpty()) {
            return Optional.empty();
        }

        Matcher m = ISO.matcher(text);
        if (m.matches()) {
            return of(m.group(1), m.group(2), m.group(3));
        }
        m = COMPACT.matcher(text);
        if (m.matches()) {
            return of(m.group(1), m.group(2), m.group(3));
        }
        m = SLASHED.matcher(text);
        if (m.matches()) {
            return order == DateOrder.DMY
                    ? of(m.group(3), m.group(2), m.group(1))
                    : of(m.group(3), m.group(1), m.group(2));
        }
        m = MONTH_NAME.matcher(text);
        if (m.matches()) {
            Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
            return month == null ? Optional.empty() : of(m.group(3), String.valueOf(month), m.group(1));
        }
        return Optional.empty();
    }

    private static String stripTime(String text) {
        // "2026-08-04T10:30:00", "2026-08-04 10:30", "04/08/2026 10:30:00": the date is the
        // first token. "12 Mar 2026" keeps its spaces because no token there has a colon.
        int t = text.indexOf('T');
        if (t > 0 && t < text.length() - 1 && Character.isDigit(text.charAt(t + 1))) {
            return text.substring(0, t);
        }
        int space = text.indexOf(' ');
        if (space > 0 && text.indexOf(':') > space) {
            return text.substring(0, space);
        }
        return text;
    }

    private static Optional<LocalDate> of(String year, String month, String day) {
        try {
            // Rejects 31 February rather than rolling it forward into March.
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)));
        } catch (DateTimeException | NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Reads a number, tolerating how exports write money.
     *
     * <p>{@code BigDecimal} rather than a double: these land in {@code numeric(14,4)}
     * columns and every margin in the product is computed from them. A double would lose
     * cents somewhere between here and a price recommendation.
     */
    public static Optional<BigDecimal> parseNumber(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = NUMBER_NOISE.matcher(raw.strip()).replaceAll("");
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }

        boolean negative = PARENTHESISED.matcher(cleaned).matches();
        if (negative) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            if (cleaned.isEmpty()) {
                return Optional.empty();
            }
        }

        try {
            BigDecimal value = new BigDecimal(cleaned);
            return Optional.of(negative ? value.negate() : value);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** {@code yes y true 1 t} and {@code no n false 0 f}; anything else is empty. */
    public static Optional<Boolean> parseBoolean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1", "t" -> Optional.of(true);
            case "no", "n", "false", "0", "f" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** A whole number, or empty when the value is not one. */
    public static Optional<Integer> parseWholeNumber(String raw) {
        return parseNumber(raw)
                .filter(value -> value.stripTrailingZeros().scale() <= 0)
                .filter(value -> value.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) < 0)
                .map(BigDecimal::intValue);
    }
}

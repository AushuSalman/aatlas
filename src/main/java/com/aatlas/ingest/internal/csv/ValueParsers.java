package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the two value types a sales export gets wrong most often.
 *
 * <p>Both return empty rather than throwing: a bad cell is a reportable row issue, not an
 * exception, because the point of the import screen is to show the user every problem at
 * once instead of stopping at the first.
 */
final class ValueParsers {

    /** {@code YYYY-MM-DD} or {@code MM/DD/YYYY}, with either separator. */
    private static final Pattern DATE =
            Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$|^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})$");

    /** Currency symbols, thousands separators and whitespace, all noise to a number. */
    private static final Pattern NUMBER_NOISE = Pattern.compile("[$£€,\\s]");

    /** Accounting's way of writing a negative: (1,234.00). */
    private static final Pattern PARENTHESISED = Pattern.compile("^\\(.*\\)$");

    private ValueParsers() {
    }

    /**
     * Reads a date.
     *
     * <p>Four-digit-year-first is unambiguous. The other form is not - {@code 03/04/2026} is
     * March in the United States and April almost everywhere else - and this market is US
     * and UK. US order is the stated assumption, and anything impossible under it (a
     * {@code 13} in the month position) is reported rather than silently swapped, because a
     * silent swap would move a transaction by up to eleven months and nobody would see it.
     */
    static Optional<LocalDate> parseDate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = DATE.matcher(raw.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        int year;
        int month;
        int day;
        if (matcher.group(1) != null) {
            year = Integer.parseInt(matcher.group(1));
            month = Integer.parseInt(matcher.group(2));
            day = Integer.parseInt(matcher.group(3));
        } else {
            month = Integer.parseInt(matcher.group(4));
            day = Integer.parseInt(matcher.group(5));
            year = Integer.parseInt(matcher.group(6));
        }

        try {
            // Rejects 31 February rather than rolling it forward into March.
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException ex) {
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
    static Optional<BigDecimal> parseNumber(String raw) {
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
}

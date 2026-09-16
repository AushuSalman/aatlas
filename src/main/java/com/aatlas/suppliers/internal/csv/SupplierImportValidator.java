package com.aatlas.suppliers.internal.csv;

import com.aatlas.common.csv.CsvReader;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.suppliers.internal.Countries;
import com.aatlas.suppliers.internal.csv.SupplierImportReport.RowIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads a supplier panel export and says what is wrong with it.
 *
 * <p>A port of {@code validateSuppliers} in the frontend's {@code supplier-csv.ts}, message
 * wording included, because the import modal shows these strings to the user verbatim.
 *
 * <p>The shape of the rules is different from the sales import and worth stating. There,
 * a doubtful row is rejected. Here, only the four required fields can reject a row; every
 * other problem is absorbed - clamped, defaulted or parsed more generously - and reported as
 * a warning. That is deliberate: a supplier master is a list of companies a buyer already
 * works with, and refusing one because its defect rate was typed as {@code 1.5%} instead of
 * {@code 1.5} would be refusing a fact about the world over a formatting opinion.
 *
 * <p>Two of those generous parses are worth knowing:
 *
 * <ul>
 *   <li><b>Rates arrive as either {@code 94} or {@code 0.94}.</b> Anything at or below 1 is
 *       read as a fraction, which is unambiguous - a supplier with a one per cent on-time
 *       rate is not a supplier anybody keeps.
 *   <li><b>Years trading is often a founding year.</b> Above 1500 it is read as a year and
 *       subtracted from today, which is how most vendor masters actually hold it.
 * </ul>
 */
@Component
public class SupplierImportValidator {

    /** Enough detail to act on; a wholly broken file produces a report, not a copy of itself. */
    private static final int ISSUE_CAP = 50;

    private static final Pattern NUMBER_NOISE = Pattern.compile("[$£€%,\\s]");
    private static final Pattern PARENTHESISED = Pattern.compile("^\\(.*\\)$");
    private static final Pattern CERT_SEPARATOR = Pattern.compile("[;,|]");

    private static final Set<String> TRUE_WORDS =
            Set.of("yes", "y", "true", "1", "stock", "instock", "exstock", "stocked");
    private static final Set<String> FALSE_WORDS =
            Set.of("no", "n", "false", "0", "maketoorder", "mto", "ordered");

    /** The categories the platform files a supplier under. */
    private static final List<String> CATEGORIES =
            List.of("Copper & brass", "Valves", "Polymers", "Steel", "Tooling", "Fittings");

    private final AatlasClock clock;

    SupplierImportValidator(AatlasClock clock) {
        this.clock = clock;
    }

    public SupplierImportReport validateWithDetectedMapping(String csv) {
        List<List<String>> rows = CsvReader.parse(csv);
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        return validate(rows, SupplierColumnMapping.detect(headers));
    }

    public SupplierImportReport validate(String csv, SupplierColumnMapping mapping) {
        return validate(CsvReader.parse(csv), mapping);
    }

    SupplierImportReport validate(List<List<String>> rows, SupplierColumnMapping mapping) {
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        List<List<String>> body = rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());
        List<SupplierField> missingRequired = mapping.missingRequired();

        if (!missingRequired.isEmpty()) {
            // Nothing is validated row by row: every row carries the same complaint, and the
            // user has one column to assign, not a thousand rows to read.
            return new SupplierImportReport(headers, mapping, body.size(), 0, 0,
                    List.of(), false, List.of(), 0, missingRequired, 0, 0);
        }

        List<RowIssue> issues = new ArrayList<>();
        // Insertion-ordered and keyed: the same supplier twice in one file is a merge, and the
        // later row wins - which is what a corrected line appended to an export is meant to do.
        Map<String, SupplierDraft> drafts = new LinkedHashMap<>();
        Set<String> countries = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        int rejected = 0;
        int duplicates = 0;
        int issueCount = 0;

        for (int index = 0; index < body.size(); index++) {
            List<String> row = body.get(index);
            int line = index + 2; // 1-based, and the header is line 1.

            List<RowIssue> fatal = checkRequired(row, mapping, line);
            if (!fatal.isEmpty()) {
                rejected++;
                issueCount += fatal.size();
                fatal.stream().limit(Math.max(0, ISSUE_CAP - issues.size())).forEach(issues::add);
                continue;
            }

            List<RowIssue> warnings = new ArrayList<>();
            SupplierDraft draft = readRow(row, mapping, line, warnings);

            String existingKey = draft.key();
            if (drafts.containsKey(existingKey)) {
                duplicates++;
                warnings.add(RowIssue.warning(line, SupplierField.NAME,
                        draft.name() + " appears more than once; this row replaced the earlier one."));
            }
            drafts.put(existingKey, draft);
            countries.add(draft.country());
            categories.add(draft.category());

            issueCount += warnings.size();
            warnings.stream().limit(Math.max(0, ISSUE_CAP - issues.size())).forEach(issues::add);
        }

        return new SupplierImportReport(
                headers,
                mapping,
                body.size(),
                drafts.size(),
                rejected,
                List.copyOf(issues),
                issueCount > issues.size(),
                List.copyOf(drafts.values()),
                duplicates,
                missingRequired,
                countries.size(),
                categories.size());
    }

    /** The four that can reject a row. Everything else is absorbed and reported. */
    private static List<RowIssue> checkRequired(List<String> row, SupplierColumnMapping mapping, int line) {
        List<RowIssue> issues = new ArrayList<>();

        String name = cell(row, mapping, SupplierField.NAME);
        if (name.isEmpty()) {
            issues.add(RowIssue.error(line, SupplierField.NAME, "No supplier name."));
        } else if (name.length() > 120) {
            issues.add(RowIssue.error(line, SupplierField.NAME, "Supplier name is longer than 120 characters."));
        }

        if (Countries.canonical(cell(row, mapping, SupplierField.COUNTRY)) == null) {
            issues.add(RowIssue.error(line, SupplierField.COUNTRY, "No country."));
        }

        String rawLead = cell(row, mapping, SupplierField.LEAD_TIME_DAYS);
        Optional<Double> lead = parseNumber(rawLead);
        if (lead.isEmpty()) {
            issues.add(RowIssue.error(line, SupplierField.LEAD_TIME_DAYS,
                    "Lead time \"" + rawLead + "\" is not a number."));
        } else if (lead.get() < 1 || lead.get() > 365) {
            issues.add(RowIssue.error(line, SupplierField.LEAD_TIME_DAYS,
                    "Lead time of " + plain(lead.get()) + " days is outside 1-365."));
        }

        String rawOtif = cell(row, mapping, SupplierField.OTIF_PCT);
        Optional<Double> otif = parseRate(rawOtif);
        if (otif.isEmpty()) {
            issues.add(RowIssue.error(line, SupplierField.OTIF_PCT,
                    "On-time \"" + rawOtif + "\" is not a number."));
        } else if (otif.get() < 1 || otif.get() > 100) {
            issues.add(RowIssue.error(line, SupplierField.OTIF_PCT,
                    "On-time of " + plain(otif.get()) + "% is outside 1-100."));
        }

        return issues;
    }

    /** Reads an accepted row, clamping and defaulting, recording every assumption as a warning. */
    private SupplierDraft readRow(
            List<String> row, SupplierColumnMapping mapping, int line, List<RowIssue> warnings) {

        String name = cell(row, mapping, SupplierField.NAME);
        String country = Countries.canonical(cell(row, mapping, SupplierField.COUNTRY));
        int leadTimeDays = (int) Math.round(parseNumber(cell(row, mapping, SupplierField.LEAD_TIME_DAYS)).orElseThrow());
        double otifPct = round(parseRate(cell(row, mapping, SupplierField.OTIF_PCT)).orElseThrow(), 2);

        double priceIndex = readRatio(row, mapping, SupplierField.PRICE_INDEX, line, warnings,
                SupplierDraft.DEFAULT_PRICE_INDEX, 40, 300,
                "Price is not a number; priced at market (100).", "Price index of %s is outside 40-300; clamped.");

        double defectPct = readBounded(row, mapping, SupplierField.DEFECT_PCT, line, warnings,
                SupplierDraft.DEFAULT_DEFECT_PCT, 0, 100,
                "Defect rate is not a number; the panel average was assumed.",
                "Defect rate of %s%% is outside 0-100; clamped.");

        double communication = readBounded(row, mapping, SupplierField.COMMUNICATION, line, warnings,
                SupplierDraft.DEFAULT_COMMUNICATION, 1, 5,
                "Communication is not a number; 3.5 ★ was assumed.",
                "Communication of %s is outside 1-5; clamped.");

        String rawCategory = cell(row, mapping, SupplierField.CATEGORY);
        String category = normaliseCategory(rawCategory);
        if (!rawCategory.isEmpty() && category == null) {
            warnings.add(RowIssue.warning(line, SupplierField.CATEGORY, "\"" + rawCategory
                    + "\" is not one of the categories; filed under " + SupplierDraft.DEFAULT_CATEGORY + "."));
        }

        int yearsTrading = readYearsTrading(row, mapping);

        String rawStock = SupplierField.normalise(cell(row, mapping, SupplierField.HOLDS_STOCK));
        boolean holdsStock = TRUE_WORDS.contains(rawStock);
        if (!rawStock.isEmpty() && !TRUE_WORDS.contains(rawStock) && !FALSE_WORDS.contains(rawStock)) {
            warnings.add(RowIssue.warning(line, SupplierField.HOLDS_STOCK, "\""
                    + cell(row, mapping, SupplierField.HOLDS_STOCK) + "\" is not yes or no; taken as made to order."));
        }

        if (!Countries.hasLane(country)) {
            warnings.add(RowIssue.warning(line, SupplierField.COUNTRY,
                    country + " has no shipping lane on file; a 30-day inbound transit is assumed."));
        }

        return new SupplierDraft(
                SupplierField.normalise(name) + "|" + SupplierField.normalise(country),
                name,
                country,
                cell(row, mapping, SupplierField.CITY),
                cleanUrl(cell(row, mapping, SupplierField.WEBSITE)),
                category == null ? SupplierDraft.DEFAULT_CATEGORY : category,
                yearsTrading,
                readCertifications(cell(row, mapping, SupplierField.CERTIFICATIONS)),
                leadTimeDays,
                otifPct,
                priceIndex,
                defectPct,
                holdsStock,
                communication,
                cell(row, mapping, SupplierField.CONTACT_NAME),
                cell(row, mapping, SupplierField.CONTACT_EMAIL),
                cell(row, mapping, SupplierField.CONTACT_PHONE),
                line);
    }

    /** A value that may arrive as a percentage or a ratio, then clamped to a range. */
    private static double readRatio(
            List<String> row, SupplierColumnMapping mapping, SupplierField field, int line,
            List<RowIssue> warnings, double fallback, double min, double max,
            String absentMessage, String clampedMessage) {

        String raw = cell(row, mapping, field);
        Optional<Double> parsed = parseNumber(raw).map(v -> v > 0 && v <= 2 ? v * 100 : v);
        return absorb(parsed, raw, field, line, warnings, fallback, min, max, absentMessage, clampedMessage);
    }

    private static double readBounded(
            List<String> row, SupplierColumnMapping mapping, SupplierField field, int line,
            List<RowIssue> warnings, double fallback, double min, double max,
            String absentMessage, String clampedMessage) {

        String raw = cell(row, mapping, field);
        return absorb(parseNumber(raw), raw, field, line, warnings, fallback, min, max, absentMessage, clampedMessage);
    }

    /**
     * Takes a value, or the assumed one, and clamps it.
     *
     * <p>A blank cell is silent - the column simply was not supplied. A cell with something
     * unreadable in it warns, because the user typed something and deserves to know it was
     * not used.
     */
    private static double absorb(
            Optional<Double> parsed, String raw, SupplierField field, int line, List<RowIssue> warnings,
            double fallback, double min, double max, String absentMessage, String clampedMessage) {

        if (parsed.isEmpty()) {
            if (!raw.isEmpty()) {
                warnings.add(RowIssue.warning(line, field, absentMessage));
            }
            return fallback;
        }
        double value = parsed.get();
        if (value < min || value > max) {
            warnings.add(RowIssue.warning(line, field, clampedMessage.formatted(plain(value))));
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    /** A count of years, or a founding year, which is how most vendor masters hold it. */
    private int readYearsTrading(List<String> row, SupplierColumnMapping mapping) {
        double years = parseNumber(cell(row, mapping, SupplierField.YEARS_TRADING))
                .orElse((double) SupplierDraft.DEFAULT_YEARS_TRADING);
        if (years > 1500) {
            years = clock.today().getYear() - years;
        }
        return (int) Math.max(0, Math.min(200, Math.round(years)));
    }

    private static List<String> readCertifications(String raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        return CERT_SEPARATOR.splitAsStream(raw).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String cell(List<String> row, SupplierColumnMapping mapping, SupplierField field) {
        return mapping.columnOf(field)
                .filter(index -> index < row.size())
                .map(index -> row.get(index).strip())
                .orElse("");
    }

    private static Optional<Double> parseNumber(String raw) {
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
            double value = new BigDecimal(cleaned).doubleValue();
            return Optional.of(negative ? -value : value);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** A rate written as either 94 or 0.94. At or below 1 it is a fraction. */
    private static Optional<Double> parseRate(String raw) {
        return parseNumber(raw).map(v -> v > 0 && v <= 1 ? v * 100 : v);
    }

    /** Exact category first, then either side containing the other, then nothing. */
    private static String normaliseCategory(String raw) {
        String key = SupplierField.normalise(raw);
        if (key.isEmpty()) {
            return null;
        }
        return CATEGORIES.stream()
                .filter(c -> SupplierField.normalise(c).equals(key))
                .findFirst()
                .or(() -> CATEGORIES.stream()
                        .filter(c -> SupplierField.normalise(c).contains(key)
                                || key.contains(SupplierField.normalise(c)))
                        .findFirst())
                .orElse(null);
    }

    /** Domain only: the scheme and a trailing slash are noise in a profile. */
    private static String cleanUrl(String raw) {
        String url = raw.strip().toLowerCase(Locale.ROOT);
        url = url.replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceFirst("/+$", "");
        return url;
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    /** Prints a number the way the file wrote it: no trailing .0 on a whole number. */
    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

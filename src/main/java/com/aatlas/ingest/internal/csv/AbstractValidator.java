package com.aatlas.ingest.internal.csv;

import com.aatlas.common.csv.CsvReader;
import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The validation loop every kind shares: parse, skip blank lines, check each row, count,
 * collect the first few accepted rows for the preview.
 *
 * <p>The rules worth knowing, because each rejects data that looks valid: an error rejects
 * the row and a warning keeps it; a file missing a required column is not validated row by
 * row (every row would carry the same complaint, and the user has one thing to fix); the
 * issue list is capped but the count is exact.
 */
abstract class AbstractValidator<R extends AcceptedRow> implements KindValidator<R> {

    /** The preview table shows this many rows, so reading more into memory is waste. */
    static final int SAMPLE_SIZE = 8;

    /**
     * A ceiling the browser version does not have, and needs here because these rows are
     * persisted. A file where every line is broken should produce a report, not a million
     * rows of one. The count is still exact; only the detail is cut.
     */
    static final int MAX_ISSUES = 500;

    /** Average month, matching the frontend's arithmetic rather than a calendar. */
    private static final double DAYS_PER_MONTH = 30.0;

    /** Cross-row state a kind may need (products: which items the file has already described). */
    static final class FileState {
        final Set<String> describedItems = new HashSet<>();
        final Set<String> seenPairs = new HashSet<>();
    }

    /** What the accepted rows add up to. */
    static final class Tally {
        final Set<String> items = new LinkedHashSet<>();
        final Set<String> customers = new LinkedHashSet<>();
        final Set<String> branches = new LinkedHashSet<>();
        final Set<String> suppliers = new LinkedHashSet<>();
        final Set<String> competitors = new LinkedHashSet<>();
        LocalDate earliest;
        LocalDate latest;

        void date(LocalDate date) {
            if (date == null) {
                return;
            }
            earliest = earliest == null || date.isBefore(earliest) ? date : earliest;
            latest = latest == null || date.isAfter(latest) ? date : latest;
        }

        static void add(Set<String> set, String value) {
            if (value != null && !value.isBlank()) {
                set.add(value.strip());
            }
        }
    }

    /** Every problem with one row. An empty list means the row is accepted as written. */
    protected abstract List<RowIssue> checkRow(
            List<String> row, ColumnMapping mapping, ValidationContext ctx, int line, FileState state);

    /** Reads an accepted row into its types. Only called for a row {@link #checkRow} passed. */
    protected abstract R toRow(List<String> row, ColumnMapping mapping, ValidationContext ctx, int line);

    protected abstract void tally(R row, Tally tally);

    @Override
    public ImportReport<R> validate(String csv, ColumnMapping mapping, ValidationContext ctx) {
        return validate(CsvReader.parse(csv), mapping, ctx);
    }

    /** Detects the mapping from the header row, then validates against it. */
    public ImportReport<R> validateWithDetectedMapping(String csv, ValidationContext ctx) {
        List<List<String>> rows = CsvReader.parse(csv);
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        return validate(rows, ColumnMapping.detect(kind(), headers), ctx);
    }

    @Override
    public void forEachAcceptedRow(String csv, ColumnMapping mapping, ValidationContext ctx, Consumer<R> consumer) {
        if (!mapping.missingRequired().isEmpty()) {
            return;
        }
        List<List<String>> rows = CsvReader.parse(csv);
        List<List<String>> body = rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());
        FileState state = new FileState();

        for (int index = 0; index < body.size(); index++) {
            List<String> row = body.get(index);
            if (isBlank(row)) {
                continue;
            }
            int line = index + 2;
            if (checkRow(row, mapping, ctx, line, state).stream().anyMatch(RowIssue::isError)) {
                continue;
            }
            consumer.accept(toRow(row, mapping, ctx, line));
        }
    }

    @Override
    public ImportReport<R> validate(List<List<String>> rows, ColumnMapping mapping, ValidationContext ctx) {
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        List<List<String>> body = rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());

        List<ImportFieldSpec> missingRequired = mapping.missingRequired();

        List<RowIssue> issues = new ArrayList<>();
        List<R> sample = new ArrayList<>();
        Tally tally = new Tally();
        FileState state = new FileState();

        int totalRows = 0;
        int accepted = 0;
        int issueCount = 0;

        for (int index = 0; index < body.size(); index++) {
            List<String> row = body.get(index);
            if (isBlank(row)) {
                continue;
            }
            totalRows++;

            // A file missing a required column is not validated row by row: every row would
            // carry the same complaint, and the user has one thing to fix, not ten thousand.
            if (!missingRequired.isEmpty()) {
                continue;
            }

            int line = index + 2; // 1-based, and the header is line 1.
            List<RowIssue> rowIssues = checkRow(row, mapping, ctx, line, state);

            issueCount += rowIssues.size();
            for (RowIssue issue : rowIssues) {
                if (issues.size() < MAX_ISSUES) {
                    issues.add(issue);
                }
            }

            if (rowIssues.stream().anyMatch(RowIssue::isError)) {
                continue;
            }

            accepted++;
            R parsed = toRow(row, mapping, ctx, line);
            tally(parsed, tally);
            if (sample.size() < SAMPLE_SIZE) {
                sample.add(parsed);
            }
        }

        return new ImportReport<>(
                headers,
                mapping,
                totalRows,
                accepted,
                totalRows - accepted,
                List.copyOf(issues),
                issueCount > issues.size(),
                List.copyOf(sample),
                tally.items.size(),
                tally.customers.size(),
                tally.branches.size(),
                tally.suppliers.size(),
                tally.competitors.size(),
                tally.earliest,
                tally.latest,
                monthsBetween(tally.earliest, tally.latest),
                missingRequired);
    }

    // ---- shared helpers ----------------------------------------------------

    static String cell(List<String> row, ColumnMapping mapping, ImportFieldSpec field) {
        return mapping.columnOf(field)
                .filter(index -> index < row.size())
                .map(index -> row.get(index).strip())
                .orElse("");
    }

    static boolean isBlank(List<String> row) {
        return row.stream().allMatch(String::isBlank);
    }

    /** No exponent and no trailing zeros, so a message reads the way the file does. */
    static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The currency rule every kind applies: blank is the tenant's currency; a recognised code
     * must be the tenant's; anything else is an error.
     */
    static Optional<RowIssue> checkCurrency(List<String> row, ColumnMapping mapping, ImportFieldSpec field,
            ValidationContext ctx, int line) {
        if (!mapping.has(field)) {
            return Optional.empty();
        }
        String raw = cell(row, mapping, field);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> code = CurrencyParser.parse(raw);
        if (code.isEmpty()) {
            return Optional.of(RowIssue.error(line, field,
                    "Currency \"" + raw + "\" is not recognised — use a 3-letter code like USD"));
        }
        if (!code.get().equals(ctx.tenantCurrency())) {
            return Optional.of(RowIssue.error(line, field,
                    "Rows in " + code.get() + " can't be loaded into a " + ctx.tenantCurrency()
                            + " workspace — export the file in " + ctx.tenantCurrency()));
        }
        return Optional.empty();
    }

    /** The currency a row is written with: always the tenant's. */
    static String writtenCurrency(ValidationContext ctx) {
        return ctx.tenantCurrency();
    }

    static int monthsBetween(LocalDate earliest, LocalDate latest) {
        if (earliest == null || latest == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(earliest, latest);
        return (int) Math.max(1, Math.round(days / DAYS_PER_MONTH));
    }
}

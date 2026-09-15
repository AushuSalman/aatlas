package com.aatlas.ingest.internal.csv;

import com.aatlas.common.csv.CsvReader;
import com.aatlas.ingest.internal.csv.ImportReport.ParsedRow;
import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads a file and says what is wrong with it.
 *
 * <p>A port of {@code validate} in the frontend's {@code ingest.ts}, including the wording
 * of every message. The wording is part of the contract: the connect screen shows these
 * strings to the user verbatim, and two spellings of the same complaint - one from the
 * browser preview, one from the server - would read as two different problems.
 *
 * <p>The rules worth knowing, because each rejects data that looks valid:
 *
 * <ul>
 *   <li><b>Quantity must be positive.</b> Credits and returns are real rows but they are not
 *       sales, and pricing them as such drags the observed price range down.
 *   <li><b>A zero price is a warning, not an error.</b> Samples and warranty replacements
 *       happen; the row is kept and flagged so the user decides.
 *   <li><b>Cost above price is a warning.</b> Either the line genuinely lost money or the
 *       cost is in a different unit of measure - and only the user knows which.
 * </ul>
 *
 * <p>Stateless, so one bean serves every concurrent upload.
 */
@Component
public class ImportValidator {

    /** The preview table shows this many rows, so reading more into memory is waste. */
    private static final int SAMPLE_SIZE = 8;

    /**
     * A ceiling the browser version does not have, and needs here because these rows are
     * persisted. A file where every line is broken should produce a report, not a million
     * rows of one. The count is still exact; only the detail is cut.
     */
    private static final int MAX_ISSUES = 500;

    /** Average month, matching the frontend's arithmetic rather than a calendar. */
    private static final double DAYS_PER_MONTH = 30.0;

    public ImportReport validate(String csv, ColumnMapping mapping) {
        return validate(CsvReader.parse(csv), mapping);
    }

    /** Detects the mapping from the header row, then validates against it. */
    public ImportReport validateWithDetectedMapping(String csv) {
        List<List<String>> rows = CsvReader.parse(csv);
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        return validate(rows, ColumnMapping.detect(headers));
    }

    /**
     * Hands every accepted row to {@code consumer}, in file order.
     *
     * <p>A callback rather than a returned list because this is what the loader uses, and a
     * 24-month export is hundreds of thousands of rows that have no reason to exist in
     * memory at once. The acceptance rule is the same one {@link #validate} applies - both
     * go through {@link #checkRow} - so a row that validated is a row that loads.
     */
    public void forEachAcceptedRow(String csv, ColumnMapping mapping, java.util.function.Consumer<ParsedRow> consumer) {
        if (!mapping.missingRequired().isEmpty()) {
            return;
        }
        List<List<String>> rows = CsvReader.parse(csv);
        List<List<String>> body = rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());

        for (int index = 0; index < body.size(); index++) {
            List<String> row = body.get(index);
            if (isBlank(row)) {
                continue;
            }
            int line = index + 2;
            if (checkRow(row, mapping, line).stream().anyMatch(RowIssue::isError)) {
                continue;
            }
            consumer.accept(toParsedRow(row, mapping, line));
        }
    }

    ImportReport validate(List<List<String>> rows, ColumnMapping mapping) {
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        List<List<String>> body = rows.size() <= 1 ? List.of() : rows.subList(1, rows.size());

        List<ImportField> missingRequired = mapping.missingRequired();

        List<RowIssue> issues = new ArrayList<>();
        List<ParsedRow> sample = new ArrayList<>();
        Set<String> items = new LinkedHashSet<>();
        Set<String> customers = new LinkedHashSet<>();
        Set<String> branches = new LinkedHashSet<>();

        int totalRows = 0;
        int accepted = 0;
        int issueCount = 0;
        LocalDate earliest = null;
        LocalDate latest = null;

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
            List<RowIssue> rowIssues = checkRow(row, mapping, line);

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
            String item = cell(row, mapping, ImportField.ITEM);
            LocalDate date = ValueParsers.parseDate(cell(row, mapping, ImportField.DATE)).orElse(null);
            String customer = cell(row, mapping, ImportField.CUSTOMER);
            String branch = cell(row, mapping, ImportField.BRANCH);

            if (!item.isEmpty()) {
                items.add(item);
            }
            if (!customer.isEmpty()) {
                customers.add(customer);
            }
            if (!branch.isEmpty()) {
                branches.add(branch);
            }
            if (date != null) {
                earliest = earliest == null || date.isBefore(earliest) ? date : earliest;
                latest = latest == null || date.isAfter(latest) ? date : latest;
            }

            if (sample.size() < SAMPLE_SIZE) {
                sample.add(toParsedRow(row, mapping, line));
            }
        }

        return new ImportReport(
                headers,
                mapping,
                totalRows,
                accepted,
                totalRows - accepted,
                List.copyOf(issues),
                issueCount > issues.size(),
                List.copyOf(sample),
                items.size(),
                customers.size(),
                branches.size(),
                earliest,
                latest,
                monthsBetween(earliest, latest),
                missingRequired);
    }

    /** Every problem with one row. An empty list means the row is accepted as written. */
    private static List<RowIssue> checkRow(List<String> row, ColumnMapping mapping, int line) {
        List<RowIssue> issues = new ArrayList<>();

        if (cell(row, mapping, ImportField.ITEM).isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.ITEM, "Item number is blank"));
        }

        String rawDate = cell(row, mapping, ImportField.DATE);
        if (ValueParsers.parseDate(rawDate).isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.DATE,
                    "Could not read the date \"" + rawDate + "\" — expected YYYY-MM-DD or MM/DD/YYYY"));
        }

        Optional<BigDecimal> qty = ValueParsers.parseNumber(cell(row, mapping, ImportField.QTY));
        if (qty.isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.QTY, "Quantity is not a number"));
        } else if (qty.get().signum() <= 0) {
            issues.add(RowIssue.error(line, ImportField.QTY, "Quantity is " + plain(qty.get())
                    + " — returns and credits should be excluded from pricing history"));
        }

        Optional<BigDecimal> price = ValueParsers.parseNumber(cell(row, mapping, ImportField.PRICE));
        if (price.isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.PRICE, "Unit price is not a number"));
        } else if (price.get().signum() == 0) {
            issues.add(RowIssue.warning(line, ImportField.PRICE,
                    "Price is zero — samples and warranty replacements should be excluded"));
        }

        if (mapping.has(ImportField.COST)) {
            String rawCost = cell(row, mapping, ImportField.COST);
            Optional<BigDecimal> cost = ValueParsers.parseNumber(rawCost);
            if (cost.isEmpty() && !rawCost.isEmpty()) {
                issues.add(RowIssue.warning(line, ImportField.COST, "Unit cost is not a number"));
            }
            if (cost.isPresent() && price.isPresent()
                    && price.get().signum() > 0
                    && cost.get().compareTo(price.get()) > 0) {
                issues.add(RowIssue.warning(line, ImportField.COST,
                        "Cost " + plain(cost.get()) + " is above price " + plain(price.get())
                                + " — sold at a loss, or the cost is a different unit of measure"));
            }
        }

        return issues;
    }

    /**
     * Reads an accepted row into its types.
     *
     * <p>Only ever called for a row {@link #checkRow} passed, which is why the numeric
     * fallbacks here are unreachable rather than lenient: an unparseable quantity is an
     * error and the row never arrives.
     */
    private static ParsedRow toParsedRow(List<String> row, ColumnMapping mapping, int line) {
        return new ParsedRow(
                cell(row, mapping, ImportField.ITEM),
                cell(row, mapping, ImportField.DESCRIPTION),
                ValueParsers.parseDate(cell(row, mapping, ImportField.DATE)).orElse(null),
                ValueParsers.parseNumber(cell(row, mapping, ImportField.QTY)).orElse(BigDecimal.ZERO),
                ValueParsers.parseNumber(cell(row, mapping, ImportField.PRICE)).orElse(BigDecimal.ZERO),
                mapping.has(ImportField.COST)
                        ? ValueParsers.parseNumber(cell(row, mapping, ImportField.COST)).orElse(null)
                        : null,
                cell(row, mapping, ImportField.CUSTOMER),
                cell(row, mapping, ImportField.BRANCH),
                line);
    }

    private static String cell(List<String> row, ColumnMapping mapping, ImportField field) {
        return mapping.columnOf(field)
                .filter(index -> index < row.size())
                .map(index -> row.get(index).strip())
                .orElse("");
    }

    private static boolean isBlank(List<String> row) {
        return row.stream().allMatch(String::isBlank);
    }

    /** No exponent and no trailing zeros, so a message reads the way the file does. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static int monthsBetween(LocalDate earliest, LocalDate latest) {
        if (earliest == null || latest == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(earliest, latest);
        return (int) Math.max(1, Math.round(days / DAYS_PER_MONTH));
    }
}

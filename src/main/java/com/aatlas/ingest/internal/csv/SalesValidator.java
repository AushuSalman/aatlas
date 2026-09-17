package com.aatlas.ingest.internal.csv;

import com.aatlas.ingest.internal.csv.ImportReport.ParsedRow;
import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import com.aatlas.ingest.internal.csv.ValueParsers.DateOrder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Validates a sales-history file.
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
 */
@Component
public class SalesValidator extends AbstractValidator<ParsedRow> {

    /** The US context the browser port assumes; the server passes the tenant's own. */
    private static final ValidationContext US = ValidationContext.of("USD", DateOrder.MDY, LocalDate.of(2026, 9, 1));

    @Override
    public ImportKind kind() {
        return ImportKind.SALES;
    }

    /** Validates in US order, as the browser does. */
    public ImportReport<ParsedRow> validate(String csv, ColumnMapping mapping) {
        return validate(csv, mapping, US);
    }

    /** Detects the mapping from the header row, then validates in US order. */
    public ImportReport<ParsedRow> validateWithDetectedMapping(String csv) {
        return validateWithDetectedMapping(csv, US);
    }

    public void forEachAcceptedRow(String csv, ColumnMapping mapping, Consumer<ParsedRow> consumer) {
        forEachAcceptedRow(csv, mapping, US, consumer);
    }

    @Override
    protected List<RowIssue> checkRow(List<String> row, ColumnMapping mapping, ValidationContext ctx, int line,
            FileState state) {
        List<RowIssue> issues = new ArrayList<>();

        if (cell(row, mapping, ImportField.ITEM).isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.ITEM, "Item number is blank"));
        }

        String rawDate = cell(row, mapping, ImportField.DATE);
        if (ValueParsers.parseDate(rawDate, ctx.dateOrder()).isEmpty()) {
            issues.add(RowIssue.error(line, ImportField.DATE, ctx.dateError(rawDate)));
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

        checkCurrency(row, mapping, ImportField.CURRENCY, ctx, line).ifPresent(issues::add);

        return issues;
    }

    /**
     * Reads an accepted row into its types.
     *
     * <p>Only ever called for a row {@link #checkRow} passed, which is why the numeric
     * fallbacks here are unreachable rather than lenient: an unparseable quantity is an
     * error and the row never arrives.
     */
    @Override
    protected ParsedRow toRow(List<String> row, ColumnMapping mapping, ValidationContext ctx, int line) {
        return new ParsedRow(
                cell(row, mapping, ImportField.ITEM),
                cell(row, mapping, ImportField.DESCRIPTION),
                ValueParsers.parseDate(cell(row, mapping, ImportField.DATE), ctx.dateOrder()).orElse(null),
                ValueParsers.parseNumber(cell(row, mapping, ImportField.QTY)).orElse(BigDecimal.ZERO),
                ValueParsers.parseNumber(cell(row, mapping, ImportField.PRICE)).orElse(BigDecimal.ZERO),
                mapping.has(ImportField.COST)
                        ? ValueParsers.parseNumber(cell(row, mapping, ImportField.COST)).orElse(null)
                        : null,
                cell(row, mapping, ImportField.CUSTOMER),
                cell(row, mapping, ImportField.BRANCH),
                line,
                limit(cell(row, mapping, ImportField.INVOICE_NO), 60),
                writtenCurrency(ctx),
                limit(cell(row, mapping, ImportField.UOM), 20));
    }

    @Override
    protected void tally(ParsedRow row, Tally tally) {
        Tally.add(tally.items, row.item());
        Tally.add(tally.customers, row.customer());
        Tally.add(tally.branches, row.branch());
        tally.date(row.date());
    }

    private static String limit(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

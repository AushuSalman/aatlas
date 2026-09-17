package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a file turned out to contain.
 *
 * <p>The shape of {@code ImportReport} in the frontend's {@code ingest.ts}, so the connect
 * screen renders a server-produced report with the same component it uses for the one it
 * computes in the browser.
 *
 * @param headers the header row as written, for the column picker
 * @param mapping which column holds which field
 * @param totalRows every non-blank data row read, before validation
 * @param acceptedRows rows with no errors - warnings still count as accepted
 * @param issues every problem found, capped; see {@link AbstractValidator}
 * @param sample the first few accepted rows, for the preview table
 * @param monthsCovered how much history was supplied, which decides whether pricing works
 * @param missingRequired required fields with no column assigned; non-empty blocks the import
 */
public record ImportReport<R extends AcceptedRow>(
        List<String> headers,
        ColumnMapping mapping,
        int totalRows,
        int acceptedRows,
        int rejectedRows,
        List<RowIssue> issues,
        boolean issuesTruncated,
        List<R> sample,
        int distinctItems,
        int distinctCustomers,
        int distinctBranches,
        int distinctSuppliers,
        int distinctCompetitors,
        LocalDate earliest,
        LocalDate latest,
        int monthsCovered,
        List<ImportFieldSpec> missingRequired) {

    /** Whether this file can be committed as it stands. */
    public boolean committable() {
        return missingRequired.isEmpty() && acceptedRows > 0;
    }

    /**
     * One problem with one row.
     *
     * @param line 1-based line number in the file, counting the header - the number a
     *     spreadsheet shows, so the user can go and look at it
     * @param field the field it concerns, or {@code null} for a whole-row problem
     * @param severity {@code error} rejects the row; {@code warning} keeps it and says so
     */
    public record RowIssue(int line, ImportFieldSpec field, String message, Severity severity) {

        public enum Severity {
            ERROR,
            WARNING;

            @com.fasterxml.jackson.annotation.JsonValue
            public String wireValue() {
                return name().toLowerCase(java.util.Locale.ROOT);
            }
        }

        static RowIssue error(int line, ImportFieldSpec field, String message) {
            return new RowIssue(line, field, message, Severity.ERROR);
        }

        static RowIssue warning(int line, ImportFieldSpec field, String message) {
            return new RowIssue(line, field, message, Severity.WARNING);
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    /**
     * One accepted sales row, read into its types.
     *
     * <p>{@code cost} is null when the file has no cost column or the cell was empty, and
     * that difference matters downstream: margin cannot be measured without it.
     */
    public record ParsedRow(
            String item,
            String description,
            LocalDate date,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal cost,
            String customer,
            String branch,
            int line,
            String invoiceNo,
            String currency,
            String uom) implements AcceptedRow {

        @Override
        public Map<String, Object> preview() {
            Map<String, Object> out = new HashMap<>();
            out.put("item", item);
            out.put("description", description);
            out.put("date", date == null ? null : date.toString());
            out.put("qty", qty);
            out.put("price", price);
            out.put("cost", cost);
            out.put("customer", customer);
            out.put("branch", branch);
            out.put("invoiceNo", invoiceNo);
            out.put("currency", currency);
            out.put("uom", uom);
            return out;
        }

        @Override
        public ParsedRow forSample(int days, BigDecimal fx) {
            return new ParsedRow(item, description, AcceptedRow.shift(date, days), qty,
                    AcceptedRow.convert(price, fx), AcceptedRow.convert(cost, fx), customer, branch, line,
                    invoiceNo, currency, uom);
        }
    }
}

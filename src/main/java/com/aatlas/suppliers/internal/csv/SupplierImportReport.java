package com.aatlas.suppliers.internal.csv;

import java.util.List;
import java.util.Locale;

/**
 * What a supplier export turned out to contain.
 *
 * <p>The shape of {@code SupplierImportReport} in the frontend's {@code supplier-csv.ts}, so
 * the import modal renders a server-produced report with the component it already has.
 *
 * @param drafts every row that can be rated, de-duplicated - the last row for a supplier wins
 * @param duplicates rows naming a supplier an earlier row already named
 * @param missingRequired required fields with no column; non-empty blocks the import
 */
public record SupplierImportReport(
        List<String> headers,
        SupplierColumnMapping mapping,
        int totalRows,
        int acceptedRows,
        int rejectedRows,
        List<RowIssue> issues,
        boolean issuesTruncated,
        List<SupplierDraft> drafts,
        int duplicates,
        List<SupplierField> missingRequired,
        int countries,
        int categories) {

    /** Whether this file can be imported as it stands. */
    public boolean committable() {
        return missingRequired.isEmpty() && acceptedRows > 0;
    }

    /**
     * One problem with one row.
     *
     * @param line 1-based, counting the header - the number a spreadsheet shows
     * @param field the field it concerns, or null for a whole-row problem
     * @param severity error rejects the row; warning keeps it and says what was assumed
     */
    public record RowIssue(int line, SupplierField field, String message, Severity severity) {

        public enum Severity {
            ERROR,
            WARNING;

            @com.fasterxml.jackson.annotation.JsonValue
            public String wireValue() {
                return name().toLowerCase(Locale.ROOT);
            }
        }

        static RowIssue error(int line, SupplierField field, String message) {
            return new RowIssue(line, field, message, Severity.ERROR);
        }

        static RowIssue warning(int line, SupplierField field, String message) {
            return new RowIssue(line, field, message, Severity.WARNING);
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }
}

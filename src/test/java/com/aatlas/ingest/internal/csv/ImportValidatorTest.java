package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.ingest.internal.csv.ImportReport.ParsedRow;
import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation, checked against the prototype's own sample file.
 *
 * <p>{@code sample-export.csv} is copied verbatim from {@code SAMPLE_CSV} in the frontend's
 * {@code ingest.ts}, where it is described as "deliberately imperfect": four rows carry
 * problems on purpose. That makes it a golden file in the same sense the pricing engine's
 * are - the browser and the server are shown the same input, and any disagreement about
 * what is wrong with it is a bug in one of them.
 *
 * <p>The planted problems, and what each must produce:
 *
 * <ul>
 *   <li>{@code 13/45/2026} - an unreadable date, rejecting the row
 *   <li>{@code -25} quantity - a credit, rejecting the row
 *   <li>{@code 0.00} price - a sample or warranty replacement, kept with a warning
 *   <li>cost {@code 14.90} against price {@code 13.05} - kept with a warning
 * </ul>
 */
class ImportValidatorTest {

    private final ImportValidator validator = new ImportValidator();

    private static String sampleExport() throws IOException {
        try (InputStream in = ImportValidatorTest.class.getResourceAsStream("/ingest/sample-export.csv")) {
            assertThat(in).as("sample-export.csv is on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the sample export produces 24 rows, 22 accepted, 2 rejected")
    void matchesThePrototypeOnTheSampleExport() throws IOException {
        ImportReport report = validator.validateWithDetectedMapping(sampleExport());

        assertThat(report.totalRows()).isEqualTo(24);
        assertThat(report.acceptedRows()).isEqualTo(22);
        assertThat(report.rejectedRows()).isEqualTo(2);
        assertThat(report.missingRequired()).isEmpty();
        assertThat(report.committable()).isTrue();
    }

    @Test
    @DisplayName("finds exactly the four planted problems, two of each severity")
    void findsThePlantedProblems() throws IOException {
        ImportReport report = validator.validateWithDetectedMapping(sampleExport());

        List<RowIssue> errors = report.issues().stream().filter(RowIssue::isError).toList();
        List<RowIssue> warnings = report.issues().stream().filter(issue -> !issue.isError()).toList();

        assertThat(errors).hasSize(2);
        assertThat(warnings).hasSize(2);
        assertThat(report.issuesTruncated()).isFalse();

        assertThat(errors).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo(ImportField.DATE);
            assertThat(issue.message()).contains("13/45/2026");
        });
        assertThat(errors).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo(ImportField.QTY);
            assertThat(issue.message()).contains("-25").contains("returns and credits");
        });
        assertThat(warnings).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo(ImportField.PRICE);
            assertThat(issue.message()).contains("Price is zero");
        });
        assertThat(warnings).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo(ImportField.COST);
            assertThat(issue.message()).contains("14.9").contains("13.05");
        });
    }

    @Test
    @DisplayName("a warning keeps its row; only errors reject")
    void warningsDoNotRejectRows() throws IOException {
        ImportReport report = validator.validateWithDetectedMapping(sampleExport());

        // Four problems, two rejections: the zero-price and cost-above-price rows are both
        // still counted as accepted.
        assertThat(report.issues()).hasSize(4);
        assertThat(report.rejectedRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("summarises the shape of the file")
    void summarisesDistinctValuesAndDateRange() throws IOException {
        ImportReport report = validator.validateWithDetectedMapping(sampleExport());

        // Counted over accepted rows only. Both rejected rows carry an item and a branch that
        // also appear on rows that passed, so nothing drops out of these counts.
        assertThat(report.distinctItems()).isEqualTo(12);
        assertThat(report.distinctBranches()).isEqualTo(8);
        assertThat(report.distinctCustomers()).isGreaterThan(5);

        assertThat(report.earliest()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(report.latest()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(report.monthsCovered()).isEqualTo(6);
    }

    @Test
    @DisplayName("the preview is the first eight accepted rows, read into their types")
    void buildsATypedSample() throws IOException {
        ImportReport report = validator.validateWithDetectedMapping(sampleExport());

        assertThat(report.sample()).hasSize(8);
        ParsedRow first = report.sample().getFirst();
        assertThat(first.item()).isEqualTo("HRD304148");
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(first.qty()).isEqualByComparingTo("120");
        assertThat(first.price()).isEqualByComparingTo("11.42");
        assertThat(first.cost()).isEqualByComparingTo("6.92");
        assertThat(first.branch()).isEqualTo("100349");
    }

    @Test
    @DisplayName("a quoted thousands separator reads as one number")
    void readsQuotedThousandsSeparators() throws IOException {
        // "2,180.00" must be 2180.00, not 2 - and it is a price, so getting it wrong would
        // move a recommendation by three orders of magnitude.
        List<ParsedRow> accepted = acceptedRows(sampleExport());

        assertThat(accepted)
                .filteredOn(row -> row.item().equals("HRD983377"))
                .first()
                .satisfies(row -> assertThat(row.price()).isEqualByComparingTo("2180.00"));
    }

    @Test
    @DisplayName("every accepted row is offered to the loader, and no rejected one is")
    void streamsExactlyTheAcceptedRows() throws IOException {
        String csv = sampleExport();
        ImportReport report = validator.validateWithDetectedMapping(csv);

        List<ParsedRow> streamed = acceptedRows(csv);

        assertThat(streamed).hasSize(report.acceptedRows());
        // The credit and the bad date must not reach the fact table.
        assertThat(streamed).allSatisfy(row -> {
            assertThat(row.qty()).isGreaterThan(BigDecimal.ZERO);
            assertThat(row.date()).isNotNull();
        });
    }

    @Test
    @DisplayName("a missing required column blocks the file instead of rejecting every row")
    void missingRequiredColumnBlocksTheImport() {
        String csv = "Item No,Item Description\nHRD1,A widget\nHRD2,Another\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.missingRequired())
                .containsExactly(ImportField.DATE, ImportField.QTY, ImportField.PRICE);
        assertThat(report.committable()).isFalse();
        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.acceptedRows()).isZero();
        // The user has one thing to fix, so they are shown one thing - not two issues per row.
        assertThat(report.issues()).isEmpty();
    }

    @Test
    @DisplayName("a file with no cost column is valid, and cost comes through as null")
    void costIsOptional() {
        String csv = "Item,Date,Qty,Price\nHRD1,2026-03-04,10,11.42\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.missingRequired()).isEmpty();
        assertThat(report.acceptedRows()).isEqualTo(1);
        assertThat(report.sample().getFirst().cost()).isNull();
    }

    @Test
    @DisplayName("US order is assumed for an ambiguous date, and an impossible one is rejected")
    void readsAmbiguousDatesInUsOrder() {
        String csv = "Item,Date,Qty,Price\nHRD1,03/04/2026,10,11.42\nHRD2,13/04/2026,10,11.42\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        // 03/04 is March, not April. Silently swapping would move a transaction by a month
        // and nobody would ever see it.
        assertThat(report.sample().getFirst().date()).isEqualTo(LocalDate.of(2026, 3, 4));
        // 13 cannot be a month, and is reported rather than read as a day.
        assertThat(report.acceptedRows()).isEqualTo(1);
        assertThat(report.issues()).anySatisfy(issue -> assertThat(issue.field()).isEqualTo(ImportField.DATE));
    }

    @Test
    @DisplayName("a date that does not exist is rejected rather than rolled forward")
    void rejectsImpossibleCalendarDates() {
        String csv = "Item,Date,Qty,Price\nHRD1,2026-02-31,10,11.42\n";

        assertThat(validator.validateWithDetectedMapping(csv).acceptedRows()).isZero();
    }

    @Test
    @DisplayName("currency symbols and parenthesised negatives are read the way accounting writes them")
    void readsMoneyTheWayExportsWriteIt() {
        String csv = "Item,Date,Qty,Price,Cost\nHRD1,2026-03-04,10,$1234.56,£2.00\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.sample().getFirst().price()).isEqualByComparingTo("1234.56");
        assertThat(report.sample().getFirst().cost()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("a blank line in the middle of a file is skipped, not counted")
    void skipsBlankLines() {
        String csv = "Item,Date,Qty,Price\nHRD1,2026-03-04,10,11.42\n,,,\nHRD2,2026-03-05,5,12.00\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.acceptedRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("issue line numbers are 1-based and count the header")
    void reportsSpreadsheetLineNumbers() {
        String csv = "Item,Date,Qty,Price\nHRD1,2026-03-04,10,11.42\nHRD2,nonsense,5,12.00\n";

        ImportReport report = validator.validateWithDetectedMapping(csv);

        // The broken row is the third line of the file, which is what a spreadsheet shows.
        assertThat(report.issues()).singleElement().satisfies(issue -> assertThat(issue.line()).isEqualTo(3));
    }

    private List<ParsedRow> acceptedRows(String csv) {
        List<List<String>> rows = CsvReader.parse(csv);
        ColumnMapping mapping = ColumnMapping.detect(rows.isEmpty() ? List.of() : rows.getFirst());
        List<ParsedRow> collected = new ArrayList<>();
        validator.forEachAcceptedRow(csv, mapping, collected::add);
        return collected;
    }
}

package com.aatlas.suppliers.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.common.time.AatlasClock;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the port against the prototype's own fixture.
 *
 * <p>The file is {@code SUPPLIER_SAMPLE_CSV} from the frontend's {@code supplier-csv.ts},
 * copied byte for byte. It is deliberately imperfect: real ERP header names, a founding year
 * where a count of years belongs, an on-time rate written as {@code 0.84}, a nameless row and
 * an unreadable lead time. If these numbers drift, the import modal's preview and the server's
 * answer have stopped agreeing, which is the one failure this port exists to prevent.
 *
 * <p>The clock is frozen because years-trading is derived from a founding year, and a test
 * that passes until New Year's Day is not a test.
 */
class SupplierImportValidatorTest {

    private static final AatlasClock CLOCK =
            AatlasClock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

    private final SupplierImportValidator validator = new SupplierImportValidator(CLOCK);

    private static String sample() throws Exception {
        try (InputStream in = SupplierImportValidatorTest.class.getResourceAsStream("/suppliers/sample-panel.csv")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SupplierImportReport report() throws Exception {
        return validator.validateWithDetectedMapping(sample());
    }

    @Test
    @DisplayName("detects every column from real ERP header names")
    void detectsColumns() throws Exception {
        SupplierColumnMapping mapping = report().mapping();

        // None of these headers is the name this product uses, which is the point.
        assertThat(mapping.columnOf(SupplierField.NAME)).contains(0);          // "Vendor Name"
        assertThat(mapping.columnOf(SupplierField.CATEGORY)).contains(4);      // "Commodity"
        assertThat(mapping.columnOf(SupplierField.LEAD_TIME_DAYS)).contains(5); // "Lead Time (days)"
        assertThat(mapping.columnOf(SupplierField.OTIF_PCT)).contains(6);      // "On Time %"
        assertThat(mapping.columnOf(SupplierField.DEFECT_PCT)).contains(8);    // "Reject Rate %"
        assertThat(mapping.columnOf(SupplierField.COMMUNICATION)).contains(9); // "Comms"
        assertThat(mapping.columnOf(SupplierField.HOLDS_STOCK)).contains(11);  // "Ex Stock"
        assertThat(mapping.columnOf(SupplierField.YEARS_TRADING)).contains(15); // "Established"
        assertThat(report().missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("six of eight rows are usable")
    void counts() throws Exception {
        SupplierImportReport report = report();

        assertThat(report.totalRows()).isEqualTo(8);
        assertThat(report.acceptedRows()).isEqualTo(6);
        // The nameless row and the one whose lead time reads "not known".
        assertThat(report.rejectedRows()).isEqualTo(2);
        assertThat(report.countries()).isEqualTo(6);
        assertThat(report.categories()).isEqualTo(6);
        assertThat(report.committable()).isTrue();
    }

    @Test
    @DisplayName("rejects only on the four required fields, and says which")
    void rejections() throws Exception {
        List<SupplierImportReport.RowIssue> errors =
                report().issues().stream().filter(SupplierImportReport.RowIssue::isError).toList();

        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).field()).isEqualTo(SupplierField.NAME);
        assertThat(errors.get(0).message()).isEqualTo("No supplier name.");
        assertThat(errors.get(1).field()).isEqualTo(SupplierField.LEAD_TIME_DAYS);
        assertThat(errors.get(1).message()).contains("not known").contains("is not a number");
    }

    @Test
    @DisplayName("an on-time rate of 0.84 is read as 84 per cent")
    void readsRatesAsEitherUnit() throws Exception {
        SupplierDraft hangzhou = draftNamed(report(), "Hangzhou Valve Works");

        // Written as a fraction in the file. A supplier delivering on time 0.84% of the
        // time is not a supplier anyone keeps, so the reading is unambiguous.
        assertThat(hangzhou.otifPct()).isEqualTo(84);
    }

    @Test
    @DisplayName("a founding year becomes a count of years")
    void readsFoundingYears() throws Exception {
        // 1998, against a clock frozen at 2026.
        assertThat(draftNamed(report(), "Meridian Copper GmbH").yearsTrading()).isEqualTo(28);
        assertThat(draftNamed(report(), "Northgate Tooling").yearsTrading()).isEqualTo(52);
    }

    @Test
    @DisplayName("normalises country, category, website and certifications")
    void normalisesFields() throws Exception {
        SupplierDraft meridian = draftNamed(report(), "Meridian Copper GmbH");

        assertThat(meridian.country()).isEqualTo("Germany");
        assertThat(meridian.category()).isEqualTo("Copper & brass");
        assertThat(meridian.website()).isEqualTo("meridiancopper.de");
        assertThat(meridian.certifications()).containsExactly("ISO 9001", "PED 2014/68/EU");
        assertThat(meridian.holdsStock()).isFalse();
        assertThat(draftNamed(report(), "Rio Verde Fittings").holdsStock()).isTrue();
    }

    @Test
    @DisplayName("a missing required column blocks the file without reporting every row")
    void missingRequiredBlocks() throws Exception {
        // Country unassigned: one thing to fix, so there is no point listing a complaint per row.
        SupplierColumnMapping partial = new SupplierColumnMapping(Map.of(
                SupplierField.NAME, 0, SupplierField.LEAD_TIME_DAYS, 5, SupplierField.OTIF_PCT, 6));

        SupplierImportReport report = validator.validate(sample(), partial);

        assertThat(report.missingRequired()).containsExactly(SupplierField.COUNTRY);
        assertThat(report.committable()).isFalse();
        assertThat(report.issues()).isEmpty();
        assertThat(report.acceptedRows()).isZero();
        assertThat(report.totalRows()).isEqualTo(8);
    }

    @Test
    @DisplayName("the same supplier twice is a merge, and the later row wins")
    void duplicatesMerge() {
        String csv = """
                Name,Country,Lead Time,On Time %
                Acme Valves,USA,14,90
                Acme Valves,USA,21,95
                """;

        SupplierImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.duplicates()).isEqualTo(1);
        assertThat(report.drafts()).hasSize(1);
        // A corrected line appended to an export is meant to replace what came before it.
        assertThat(report.drafts().getFirst().leadTimeDays()).isEqualTo(21);
        assertThat(report.issues()).anyMatch(i -> i.message().contains("appears more than once"));
    }

    @Test
    @DisplayName("absent optionals are assumed silently; unreadable ones warn")
    void assumptionsAreReported() {
        String csv = """
                Name,Country,Lead Time,On Time %,Price vs Market,Comms
                Quiet Co,USA,10,95,,
                Noisy Co,USA,10,95,about market,lots
                """;

        SupplierImportReport report = validator.validateWithDetectedMapping(csv);
        assertThat(report.acceptedRows()).isEqualTo(2);

        // A blank cell means the column was not supplied, which is not worth a complaint.
        assertThat(report.issues()).noneMatch(i -> i.line() == 2);
        // Something unreadable was typed, so the user is told it was not used.
        assertThat(report.issues()).anyMatch(i -> i.line() == 3 && i.field() == SupplierField.PRICE_INDEX);
        assertThat(report.drafts().getFirst().priceIndex()).isEqualTo(100);
    }

    @Test
    @DisplayName("a country with no lane still imports, with the assumption stated")
    void unknownCountryWarnsRatherThanRejects() {
        String csv = """
                Name,Country,Lead Time,On Time %
                Kaunas Metal,Lithuania,20,92
                """;

        SupplierImportReport report = validator.validateWithDetectedMapping(csv);

        assertThat(report.acceptedRows()).isEqualTo(1);
        assertThat(report.issues()).anyMatch(i -> i.message().contains("no shipping lane on file"));
    }

    private static SupplierDraft draftNamed(SupplierImportReport report, String name) {
        return report.drafts().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No draft named " + name));
    }
}

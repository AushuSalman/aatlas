package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Column detection, which is the first thing the user sees working or not working.
 *
 * <p>The headers here are the ones real exports use. Detection that needs the file renamed
 * to this product's vocabulary first would not be detection.
 */
class ColumnMappingTest {

    @Test
    @DisplayName("finds all eight columns in the sample export's headers")
    void detectsEveryColumnInTheSampleExport() {
        ColumnMapping mapping = ColumnMapping.detect(List.of(
                "Item No", "Item Description", "Invoice Date", "Qty Shipped",
                "Net Price", "Unit Cost", "Bill To", "Whse"));

        assertThat(mapping.columnOf(ImportField.ITEM)).contains(0);
        assertThat(mapping.columnOf(ImportField.DESCRIPTION)).contains(1);
        assertThat(mapping.columnOf(ImportField.DATE)).contains(2);
        assertThat(mapping.columnOf(ImportField.QTY)).contains(3);
        assertThat(mapping.columnOf(ImportField.PRICE)).contains(4);
        assertThat(mapping.columnOf(ImportField.COST)).contains(5);
        assertThat(mapping.columnOf(ImportField.CUSTOMER)).contains(6);
        assertThat(mapping.columnOf(ImportField.BRANCH)).contains(7);
        assertThat(mapping.missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("ignores case, spaces and separators in a header")
    void normalisesHeaders() {
        ColumnMapping mapping = ColumnMapping.detect(List.of("ITEM_NO", "qty shipped", "net-price", "Invoice.Date"));

        assertThat(mapping.columnOf(ImportField.ITEM)).contains(0);
        assertThat(mapping.columnOf(ImportField.QTY)).contains(1);
        assertThat(mapping.columnOf(ImportField.PRICE)).contains(2);
        assertThat(mapping.columnOf(ImportField.DATE)).contains(3);
    }

    @Test
    @DisplayName("an exact match is never lost to a fuzzier one")
    void prefersExactMatches() {
        // "shipqtyordered" only matches by containing "shipqty"; "quantity" is an exact
        // synonym. The exact pass runs across every column first, so the later exact match
        // wins over the earlier fuzzy one.
        ColumnMapping mapping = ColumnMapping.detect(List.of("Ship Qty Ordered", "Quantity"));

        assertThat(mapping.columnOf(ImportField.QTY)).contains(1);
    }

    @Test
    @DisplayName("a header that is itself a synonym matches exactly, wherever it sits")
    void matchesSynonymHeadersExactly() {
        // "Qty Shipped" is in the synonym list in its own right, so this is an exact match
        // rather than a fuzzy one - which is why it beats a later "quantity".
        ColumnMapping mapping = ColumnMapping.detect(List.of("Qty Shipped", "Quantity"));

        assertThat(mapping.columnOf(ImportField.QTY)).contains(0);
    }

    @Test
    @DisplayName("a column is claimed once, so two fields never read the same values")
    void neverAssignsAColumnTwice() {
        // Both "cost" and "unit cost" would answer to the cost field; only one can have it.
        ColumnMapping mapping = ColumnMapping.detect(List.of("Unit Cost", "Cost"));

        assertThat(mapping.columns().values()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a short synonym does not match inside an unrelated header")
    void doesNotMatchShortSynonymsAsSubstrings() {
        // "cust" is four characters, so the contains pass ignores it; without that rule
        // "Customer Adjustment Code" would be read as the customer column.
        ColumnMapping mapping = ColumnMapping.detect(List.of("Item No", "Adjustment Code"));

        assertThat(mapping.columnOf(ImportField.CUSTOMER)).isEmpty();
    }

    @Test
    @DisplayName("unmatched headers are left alone rather than guessed at")
    void leavesUnknownHeadersUnassigned() {
        ColumnMapping mapping = ColumnMapping.detect(List.of("Item No", "Ledger Ref", "Posting Batch"));

        assertThat(mapping.columns()).containsOnlyKeys(ImportField.ITEM);
        // A guess here would produce a plausible number that is wrong, which is worse than
        // asking the user.
        assertThat(mapping.missingRequired())
                .containsExactly(ImportField.DATE, ImportField.QTY, ImportField.PRICE);
    }

    @Test
    @DisplayName("missingRequired names only required fields")
    void missingRequiredIgnoresOptionalFields() {
        ColumnMapping mapping = ColumnMapping.detect(List.of("Item", "Date", "Qty", "Price"));

        // Cost, customer, branch and description are all absent and none of them blocks.
        assertThat(mapping.missingRequired()).isEmpty();
    }
}

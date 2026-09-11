package com.aatlas.ingest.internal.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parsing quirks real exports carry.
 *
 * <p>Each case here is something an ERP actually emits. Getting any of them wrong shifts
 * every column after it, which produces a plausible import of entirely wrong data - the
 * worst failure this pipeline has, because nothing about it looks broken.
 */
class CsvReaderTest {

    @Test
    @DisplayName("splits a plain file into header and rows")
    void readsPlainRows() {
        List<List<String>> rows = CsvReader.parse("a,b,c\n1,2,3\n4,5,6\n");

        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst()).containsExactly("a", "b", "c");
        assertThat(rows.get(2)).containsExactly("4", "5", "6");
    }

    @Test
    @DisplayName("a comma inside quotes is data, not a separator")
    void keepsCommasInsideQuotes() {
        // Straight from the sample export: a product description with a comma in it.
        List<List<String>> rows =
                CsvReader.parse("item,description,price\nHRD335590,\"CHROME FAUCET 4 IN CENTERSET, 2-HANDLE\",148.00\n");

        assertThat(rows.get(1))
                .containsExactly("HRD335590", "CHROME FAUCET 4 IN CENTERSET, 2-HANDLE", "148.00");
    }

    @Test
    @DisplayName("a thousands separator inside quotes does not split the number")
    void keepsQuotedThousandsSeparators() {
        List<List<String>> rows = CsvReader.parse("qty,price\n3,\"2,180.00\"\n");

        assertThat(rows.get(1)).containsExactly("3", "2,180.00");
    }

    @Test
    @DisplayName("a doubled quote inside a quoted field is one literal quote")
    void unescapesDoubledQuotes() {
        List<List<String>> rows = CsvReader.parse("item,description\nX1,\"1/2\"\" COPPER TUBE\"\n");

        assertThat(rows.get(1)).containsExactly("X1", "1/2\" COPPER TUBE");
    }

    @Test
    @DisplayName("a newline inside quotes stays inside the field")
    void keepsNewlinesInsideQuotes() {
        List<List<String>> rows = CsvReader.parse("item,note\nX1,\"line one\nline two\"\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(1)).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("CRLF endings read the same as LF")
    void handlesWindowsLineEndings() {
        List<List<String>> rows = CsvReader.parse("a,b\r\n1,2\r\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("1", "2");
    }

    @Test
    @DisplayName("the byte-order mark Excel adds is not part of the first header")
    void stripsByteOrderMark() {
        // Without this the first column is named "﻿Item No" and never matches a synonym,
        // so detection silently loses the item column on every file Excel has touched.
        List<List<String>> rows = CsvReader.parse("﻿Item No,Qty\nX1,5\n");

        assertThat(rows.getFirst().getFirst()).isEqualTo("Item No");
    }

    @Test
    @DisplayName("a trailing newline does not produce a blank row")
    void dropsTrailingBlankRow() {
        assertThat(CsvReader.parse("a,b\n1,2\n")).hasSize(2);
        assertThat(CsvReader.parse("a,b\n1,2")).hasSize(2);
    }

    @Test
    @DisplayName("empty fields are preserved, so column positions never shift")
    void keepsEmptyFields() {
        List<List<String>> rows = CsvReader.parse("a,b,c\n1,,3\n");

        assertThat(rows.get(1)).containsExactly("1", "", "3");
    }

    @Test
    @DisplayName("an empty input is no rows rather than one empty row")
    void handlesEmptyInput() {
        assertThat(CsvReader.parse("")).isEmpty();
        assertThat(CsvReader.parse("\n")).isEmpty();
    }
}

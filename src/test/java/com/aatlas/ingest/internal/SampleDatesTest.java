package com.aatlas.ingest.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.common.csv.CsvReader;
import com.aatlas.ingest.internal.csv.ValueParsers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The generator's calendar and {@link SampleDates} must agree, or every sample row could land in the future. */
class SampleDatesTest {

    @Test
    @DisplayName("the classpath sales sample ends exactly on the anchor")
    void salesSampleEndsOnTheAnchor() throws IOException {
        LocalDate latest = null;
        LocalDate earliest = null;
        for (List<String> row : rows("/samples/sales-history.csv")) {
            LocalDate date = ValueParsers.parseDate(row.get(2)).orElseThrow();
            latest = latest == null || date.isAfter(latest) ? date : latest;
            earliest = earliest == null || date.isBefore(earliest) ? date : earliest;
        }
        assertThat(latest).isEqualTo(SampleDates.ANCHOR);
        assertThat(earliest).isEqualTo(LocalDate.of(2024, 7, 1));
    }

    @Test
    @DisplayName("no purchase, observation or receipt in the other samples is after the anchor")
    void otherSamplesNeverPassTheAnchor() throws IOException {
        for (List<String> row : rows("/samples/purchase-history.csv")) {
            assertThat(ValueParsers.parseDate(row.get(1)).orElseThrow()).isBeforeOrEqualTo(SampleDates.ANCHOR);
            if (!row.get(14).isBlank()) {
                assertThat(ValueParsers.parseDate(row.get(14)).orElseThrow()).isBeforeOrEqualTo(SampleDates.ANCHOR);
            }
        }
        for (List<String> row : rows("/samples/competitor-prices.csv")) {
            assertThat(ValueParsers.parseDate(row.get(5)).orElseThrow()).isBeforeOrEqualTo(SampleDates.ANCHOR);
        }
    }

    @Test
    @DisplayName("the offset is whole days from the anchor, never negative")
    void offsetDays() {
        assertThat(SampleDates.offsetDays(LocalDate.of(2026, 8, 28))).isZero();
        assertThat(SampleDates.offsetDays(LocalDate.of(2026, 9, 1))).isEqualTo(4);
        assertThat(SampleDates.offsetDays(LocalDate.of(2026, 9, 17))).isEqualTo(20);
        assertThat(SampleDates.offsetDays(LocalDate.of(2026, 8, 1))).isZero();
    }

    private static List<List<String>> rows(String resource) throws IOException {
        try (InputStream in = SampleDatesTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            List<List<String>> all = CsvReader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return all.subList(1, all.size());
        }
    }
}

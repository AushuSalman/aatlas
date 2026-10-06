package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleMathTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static Instant at(String s) {
        return Instant.parse(s);
    }

    @Test
    void oneTimeRunsAtItsStartThenIsDone() {
        Instant start = at("2026-10-10T13:00:00Z");
        assertThat(ScheduleMath.next(start, null, null, NY, at("2026-10-06T00:00:00Z"), null)).isEqualTo(start);
        // Start already passed and never ran: run now.
        Instant now = at("2026-10-11T00:00:00Z");
        assertThat(ScheduleMath.next(start, null, null, NY, now, null)).isEqualTo(now);
        // Ran at or after its start: done.
        assertThat(ScheduleMath.next(start, null, null, NY, now, at("2026-10-10T13:00:05Z"))).isNull();
        // Ran by hand before its start: still runs at its start.
        assertThat(ScheduleMath.next(start, null, null, NY, at("2026-10-06T00:00:00Z"), at("2026-10-05T00:00:00Z")))
                .isEqualTo(start);
    }

    @Test
    void repeatsCountFromTheStart() {
        Instant start = at("2026-10-01T13:00:00Z");
        assertThat(ScheduleMath.next(start, 1, "days", NY, at("2026-10-06T14:00:00Z"), null))
                .isEqualTo(at("2026-10-07T13:00:00Z"));
        assertThat(ScheduleMath.next(start, 1, "days", NY, at("2026-10-06T13:00:00Z"), null))
                .isEqualTo(at("2026-10-06T13:00:00Z"));
        assertThat(ScheduleMath.next(start, 2, "weeks", NY, at("2026-10-06T00:00:00Z"), null))
                .isEqualTo(at("2026-10-15T13:00:00Z"));
        assertThat(ScheduleMath.next(start, 6, "hours", NY, at("2026-10-01T20:00:00Z"), null))
                .isEqualTo(at("2026-10-02T01:00:00Z"));
        // Not started yet: the start.
        assertThat(ScheduleMath.next(start, 1, "days", NY, at("2026-09-01T00:00:00Z"), null)).isEqualTo(start);
    }

    @Test
    void dailyKeepsItsLocalTimeAcrossTheClockChange() {
        // 09:00 New York: 13:00Z in summer time, 14:00Z after 1 Nov 2026.
        Instant start = at("2026-10-30T13:00:00Z");
        assertThat(ScheduleMath.next(start, 1, "days", NY, at("2026-11-03T00:00:00Z"), null))
                .isEqualTo(at("2026-11-03T14:00:00Z"));
    }

    @Test
    void monthlyFromThe31stLandsOnShortMonthsWithoutDrifting() {
        Instant start = at("2026-01-31T15:00:00Z");
        ZoneId utc = ZoneId.of("UTC");
        assertThat(ScheduleMath.next(start, 1, "months", utc, at("2026-02-15T00:00:00Z"), null))
                .isEqualTo(at("2026-02-28T15:00:00Z"));
        assertThat(ScheduleMath.next(start, 1, "months", utc, at("2026-03-01T00:00:00Z"), null))
                .isEqualTo(at("2026-03-31T15:00:00Z"));
    }

    @Test
    void runsPerMonth() {
        assertThat(ScheduleMath.runsPerMonth(null, null)).isEqualTo(1);
        assertThat(ScheduleMath.runsPerMonth(1, "days")).isEqualTo(30);
        assertThat(ScheduleMath.runsPerMonth(12, "hours")).isEqualTo(60);
        assertThat(ScheduleMath.runsPerMonth(1, "weeks")).isEqualTo(30 / 7.0);
    }

    @Test
    void aScheduleWithoutANameIsNamedFromWhatItCoversAndHowOften() {
        assertThat(PriceSchedulesService.autoName("all", List.of(), List.of(), 1, "days"))
                .isEqualTo("All products · every day");
        assertThat(PriceSchedulesService.autoName("categories", List.of("Plumbing", "HVAC", "Fixtures"), List.of(), 2,
                "weeks")).isEqualTo("Plumbing, HVAC +1 more · every 2 weeks");
        assertThat(PriceSchedulesService.autoName("items", List.of(), List.of("W-12"), null, null))
                .isEqualTo("W-12 · once");
        assertThat(PriceSchedulesService.autoName("items", List.of(), List.of("A", "B"), 6, "hours"))
                .isEqualTo("2 products · every 6 hours");
    }
}

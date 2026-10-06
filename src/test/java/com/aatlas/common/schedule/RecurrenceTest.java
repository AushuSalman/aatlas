package com.aatlas.common.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecurrenceTest {

    private static Instant at(String s) {
        return Instant.parse(s);
    }

    @Test
    void dailyRunsAtItsLocalTimeAndKeepsItAcrossTheClockChange() {
        Recurrence r = Recurrence.of("daily", "05:00", null, null, "America/New_York");
        // 05:00 New York is 09:00Z in summer time and 10:00Z after 1 Nov 2026.
        assertThat(r.next(at("2026-10-06T08:00:00Z"))).isEqualTo(at("2026-10-06T09:00:00Z"));
        assertThat(r.next(at("2026-10-06T09:00:00Z"))).isEqualTo(at("2026-10-07T09:00:00Z"));
        assertThat(r.next(at("2026-11-02T00:00:00Z"))).isEqualTo(at("2026-11-02T10:00:00Z"));
        assertThat(r.describe()).isEqualTo("Every day at 05:00");
    }

    @Test
    void weeklyAndMonthly() {
        Recurrence weekly = Recurrence.of("weekly", "06:30", 1, null, "UTC");
        // 6 Oct 2026 is a Tuesday: next Monday is the 12th.
        assertThat(weekly.next(at("2026-10-06T12:00:00Z"))).isEqualTo(at("2026-10-12T06:30:00Z"));
        assertThat(weekly.describe()).isEqualTo("Every Monday at 06:30");

        Recurrence monthly = Recurrence.of("monthly", "02:00", null, 1, "UTC");
        assertThat(monthly.next(at("2026-10-06T12:00:00Z"))).isEqualTo(at("2026-11-01T02:00:00Z"));
        assertThat(monthly.describe()).isEqualTo("On the 1st of every month at 02:00");
    }

    @Test
    void manualNeverRunsOnItsOwn() {
        Recurrence r = Recurrence.of("manual", null, null, null, null);
        assertThat(r.next(at("2026-10-06T12:00:00Z"))).isNull();
    }

    @Test
    void badChoicesAreRefusedInWords() {
        assertThatThrownBy(() -> Recurrence.of("hourly", "05:00", null, null, "UTC"))
                .hasMessageContaining("daily, weekly, monthly or manual");
        assertThatThrownBy(() -> Recurrence.of("monthly", "05:00", null, 31, "UTC")).hasMessageContaining("1 to 28");
        assertThatThrownBy(() -> Recurrence.of("daily", "5pm", null, null, "UTC")).hasMessageContaining("05:30");
    }

    @Test
    void dueOnItsTimeOrOnEnoughNewDataAfterTheCoolDown() {
        Recurrence r = Recurrence.of("weekly", "05:00", 1, null, "UTC");
        Instant now = at("2026-10-06T12:00:00Z");
        TrainingSchedules.Schedule scheduled = new TrainingSchedules.Schedule(r, false, 50, at("2026-10-06T05:00:00Z"),
                null, null, null, true);
        assertThat(TrainingSchedules.due(scheduled, now, 0).why()).isEqualTo("scheduled");

        TrainingSchedules.Schedule watching = new TrainingSchedules.Schedule(r, true, 50, at("2026-10-12T05:00:00Z"),
                at("2026-10-06T11:50:00Z"), "trained", null, true);
        // Enough new sales, but the last run was ten minutes ago: wait.
        assertThat(TrainingSchedules.due(watching, now, 80).due()).isFalse();
        assertThat(TrainingSchedules.due(watching, at("2026-10-06T12:30:00Z"), 80).why()).isEqualTo("new-data");
        assertThat(TrainingSchedules.due(watching, at("2026-10-06T12:30:00Z"), 10).due()).isFalse();
    }
}

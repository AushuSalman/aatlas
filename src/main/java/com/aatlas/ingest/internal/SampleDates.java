package com.aatlas.ingest.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How the Hardin sample's calendar is re-based to the tenant's.
 *
 * <p>The generator writes 26 months ending on {@link #ANCHOR}, the last sales date. At load
 * every date column of every sample file is moved forward by {@link #offsetDays}, so the
 * history always ends today and no sample row is ever in the future. Whole days rather than
 * whole months, because the trailing-90-day window is what the demo and the tests read most,
 * and it must hold the same rows whatever the calendar says. Uploads are never shifted.
 */
public final class SampleDates {

    /** The last sales date the generator writes. {@code SampleDatesTest} pins the file to it. */
    public static final LocalDate ANCHOR = LocalDate.of(2026, 8, 28);

    private SampleDates() {
    }

    /** Days to add to every sample date so the latest one lands on {@code today}; never negative. */
    public static int offsetDays(LocalDate today) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(ANCHOR, today));
    }
}

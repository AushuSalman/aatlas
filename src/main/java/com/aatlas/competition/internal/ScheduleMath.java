package com.aatlas.competition.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * When a price-check schedule runs next. Repeats are counted from the start in the schedule's
 * own time zone, so "every day at 09:00" stays at 09:00 across a clock change and "every month
 * on the 31st" lands on the month's last day.
 */
final class ScheduleMath {

    private ScheduleMath() {
    }

    /** The shortest repeat allowed: every product, every source, each run - the searches add up. */
    static final int MIN_HOURS = 6;

    /**
     * The next run at or after {@code now}.
     *
     * @param every null for a one-time schedule
     * @param lastRunAt when it last ran, or null; a one-time schedule that ran at or after its
     *        start is done (null)
     */
    static Instant next(Instant startsAt, Integer every, String unit, ZoneId zone, Instant now, Instant lastRunAt) {
        if (every == null) {
            if (lastRunAt != null && !lastRunAt.isBefore(startsAt)) {
                return null;
            }
            return startsAt.isAfter(now) ? startsAt : now;
        }
        if (!startsAt.isBefore(now)) {
            return startsAt;
        }
        if ("hours".equals(unit)) {
            long periods = Duration.between(startsAt, now).toHours() / every;
            Instant at = startsAt.plus(Duration.ofHours(periods * every));
            return at.isBefore(now) ? at.plus(Duration.ofHours(every)) : at;
        }
        ChronoUnit u = switch (unit) {
            case "days" -> ChronoUnit.DAYS;
            case "weeks" -> ChronoUnit.WEEKS;
            case "months" -> ChronoUnit.MONTHS;
            default -> throw new IllegalArgumentException("repeat unit " + unit);
        };
        ZonedDateTime start = startsAt.atZone(zone);
        // Near the answer by arithmetic, then step - always from the start, so a short month never shifts later runs.
        long k = Math.max(0, u.between(start, now.atZone(zone)) / every - 1);
        ZonedDateTime at = start.plus(k * every, u);
        while (at.toInstant().isBefore(now)) {
            k++;
            at = start.plus(k * every, u);
        }
        return at.toInstant();
    }

    /** Roughly how many runs a 30-day month holds; 1 for a one-time schedule. */
    static double runsPerMonth(Integer every, String unit) {
        if (every == null) {
            return 1;
        }
        double days = switch (unit) {
            case "hours" -> every / 24.0;
            case "days" -> every;
            case "weeks" -> every * 7.0;
            default -> every * 30.0;
        };
        return 30.0 / days;
    }
}

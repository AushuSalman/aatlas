package com.aatlas.common.schedule;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * When a recurring job runs: every day, a day of the week or a day of the month at a local time,
 * or only when someone asks ({@code manual}). Counted in the tenant's own time zone, so "daily at
 * 05:00" stays at 05:00 across a clock change. Pure; no clock read.
 *
 * @param frequency {@code daily}, {@code weekly}, {@code monthly} or {@code manual}
 * @param at the local time of day
 * @param weekday 1 (Monday) to 7 (Sunday), for {@code weekly}
 * @param monthDay 1 to 28, for {@code monthly} - every month has it
 */
public record Recurrence(String frequency, LocalTime at, Integer weekday, Integer monthDay, ZoneId zone) {

    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";
    public static final String MANUAL = "manual";
    public static final List<String> FREQUENCIES = List.of(DAILY, WEEKLY, MONTHLY, MANUAL);

    /**
     * Checks and normalises what a person chose; throws {@link IllegalArgumentException} with a
     * sentence they can read.
     */
    public static Recurrence of(String frequency, String at, Integer weekday, Integer monthDay, String timeZone) {
        String f = frequency == null ? "" : frequency.strip().toLowerCase(Locale.ROOT);
        if (!FREQUENCIES.contains(f)) {
            throw new IllegalArgumentException("How often must be daily, weekly, monthly or manual.");
        }
        LocalTime time;
        try {
            time = at == null || at.isBlank() ? LocalTime.of(5, 0) : LocalTime.parse(at.strip()).withSecond(0).withNano(0);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("The time must look like 05:30.");
        }
        ZoneId zone;
        try {
            zone = timeZone == null || timeZone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(timeZone.strip());
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Unknown time zone: " + timeZone);
        }
        Integer wd = null;
        Integer md = null;
        if (WEEKLY.equals(f)) {
            wd = weekday == null ? 1 : weekday;
            if (wd < 1 || wd > 7) {
                throw new IllegalArgumentException("The weekday must be 1 (Monday) to 7 (Sunday).");
            }
        } else if (MONTHLY.equals(f)) {
            md = monthDay == null ? 1 : monthDay;
            if (md < 1 || md > 28) {
                throw new IllegalArgumentException("The day of the month must be 1 to 28, so every month has it.");
            }
        }
        return new Recurrence(f, time, wd, md, zone);
    }

    public boolean manual() {
        return MANUAL.equals(frequency);
    }

    /** The first run strictly after {@code after}; null for {@code manual}. */
    public Instant next(Instant after) {
        if (manual()) {
            return null;
        }
        ZonedDateTime local = after.atZone(zone);
        LocalDate day = local.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate d = day.plusDays(i);
            boolean fits = switch (frequency) {
                case WEEKLY -> d.getDayOfWeek().getValue() == weekday;
                case MONTHLY -> d.getDayOfMonth() == monthDay;
                default -> true;
            };
            if (!fits) {
                continue;
            }
            Instant at2 = ZonedDateTime.of(d, at, zone).toInstant();
            if (at2.isAfter(after)) {
                return at2;
            }
        }
        return null;
    }

    /** "Every day at 05:00", "Every Monday at 05:00", "On the 1st of every month at 05:00", "Only when you click". */
    public String describe() {
        String time = at.toString();
        return switch (frequency) {
            case DAILY -> "Every day at " + time;
            case WEEKLY -> "Every " + DayOfWeek.of(weekday).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " at " + time;
            case MONTHLY -> "On the " + ordinal(monthDay) + " of every month at " + time;
            default -> "Only when you click Train";
        };
    }

    private static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}

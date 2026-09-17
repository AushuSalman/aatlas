package com.aatlas.history;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * An inclusive date range, and the derived dates the SQL binds.
 *
 * <p>Every query in this module binds precomputed {@link LocalDate} parameters rather than
 * doing arithmetic on a bind ({@code ? - 89} resolves as an integer through pgjdbc). The
 * accessors here are those parameters: {@link #r0()} opens the trailing-90-day window,
 * {@link #p0()} the 90 days before it, {@link #d30()} the trailing 30 days.
 *
 * @param from first day, inclusive
 * @param to last day, inclusive - "today" for a trailing window
 */
public record Window(LocalDate from, LocalDate to) {

    public Window {
        if (from == null || to == null) {
            throw new IllegalArgumentException("A window needs both ends");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Window ends before it starts: " + from + " > " + to);
        }
    }

    /** The {@code n} months ending today: {@code [today - n months + 1 day, today]}. */
    public static Window trailingMonths(LocalDate today, int months) {
        return new Window(today.minusMonths(months).plusDays(1), today);
    }

    /** The {@code n} days ending today: {@code [today - (n - 1), today]}. */
    public static Window trailingDays(LocalDate today, int days) {
        return new Window(today.minusDays(days - 1L), today);
    }

    /** The window of the same length that ends the day before this one starts. */
    public Window prior() {
        long length = days();
        return new Window(from.minusDays(length), from.minusDays(1));
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Whether the date falls inside the window. */
    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** First day of the trailing 90 days ending {@link #to()}. */
    public LocalDate r0() {
        return to.minusDays(89);
    }

    /** The day before {@link #r0()}: the end of the prior 90 days. */
    public LocalDate r1() {
        return to.minusDays(90);
    }

    /** First day of the 90 days before the trailing 90: {@code to - 179}. */
    public LocalDate p0() {
        return to.minusDays(179);
    }

    /** First day of the trailing 30 days ending {@link #to()}. */
    public LocalDate d30() {
        return to.minusDays(29);
    }

    /** First day of the month {@code months - 1} months before {@link #to()}'s month. */
    public LocalDate m0(int months) {
        return to.withDayOfMonth(1).minusMonths(months - 1L);
    }

    /** First day of {@link #to()}'s month. */
    public LocalDate m1() {
        return to.withDayOfMonth(1);
    }
}

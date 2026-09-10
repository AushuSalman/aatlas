package com.aatlas.analytics.internal.ledger;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Range presets, the comparison window and chart bucketing. Ported from
 * {@code platform/procurement.ts}'s {@code resolveRange}/{@code previousRange}/{@code bucketsFor}.
 */
public final class DateRanges {

    private static final String[] MONTH_SHORT =
            {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    public record RangePreset(String key, String label, String hint) {
    }

    public static final List<RangePreset> PRESETS = List.of(
            new RangePreset("7d", "Last 7 days", "This week of ordering"),
            new RangePreset("30d", "Last 30 days", "The current buying cycle"),
            new RangePreset("90d", "Last 90 days", "A quarter of purchasing"),
            new RangePreset("qtd", "Quarter to date", "Since the quarter opened"),
            new RangePreset("ytd", "Year to date", "Against the annual plan"),
            new RangePreset("12m", "Last 12 months", "A full seasonal cycle"),
            new RangePreset("24m", "Last 24 months", "Everything on file"));

    private DateRanges() {
    }

    public static String fmtDate(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTH_SHORT[d.getMonthValue() - 1] + " " + d.getYear();
    }

    public static int daysBetween(LocalDate a, LocalDate b) {
        return (int) ChronoUnit.DAYS.between(a, b);
    }

    private static DateRange make(String key, LocalDate from, LocalDate to, String label) {
        return new DateRange(key, from, to, label, daysBetween(from, to) + 1);
    }

    public static DateRange resolveRange(String key, LocalDate today) {
        return resolveRange(key, today, null, null);
    }

    /** {@code customFrom}/{@code customTo} are used only when {@code key} is not a known preset. */
    public static DateRange resolveRange(String key, LocalDate today, LocalDate customFrom, LocalDate customTo) {
        LocalDate to = today;
        switch (key) {
            case "7d":
                return make("7d", to.minusDays(6), to, "Last 7 days");
            case "30d":
                return make("30d", to.minusDays(29), to, "Last 30 days");
            case "90d":
                return make("90d", to.minusDays(89), to, "Last 90 days");
            case "qtd": {
                int q = ((today.getMonthValue() - 1) / 3) * 3;
                LocalDate from = LocalDate.of(today.getYear(), q + 1, 1);
                return make("qtd", from, to, "Quarter to date");
            }
            case "ytd": {
                LocalDate from = LocalDate.of(today.getYear(), 1, 1);
                return make("ytd", from, to, "Year to date");
            }
            case "12m":
                return make("12m", to.minusDays(364), to, "Last 12 months");
            case "24m":
                return make("24m", to.minusDays(729), to, "Last 24 months");
            default: {
                LocalDate f = customFrom != null ? customFrom : to.minusDays(29);
                LocalDate t = customTo != null ? customTo : to;
                LocalDate lo = !f.isAfter(t) ? f : t;
                LocalDate hi = !f.isAfter(t) ? t : f;
                return make("custom", lo, hi, fmtDate(lo) + " — " + fmtDate(hi));
            }
        }
    }

    /** The same length of time immediately before a range. Every delta is measured against it. */
    public static DateRange previousRange(DateRange r) {
        LocalDate to = r.from().minusDays(1);
        LocalDate from = to.minusDays(r.days() - 1L);
        return new DateRange(r.key(), from, to, "previous " + r.days() + " days", r.days());
    }

    public enum BucketUnit { DAY, WEEK, MONTH }

    public record Buckets(BucketUnit unit, List<Bucket> buckets) {
    }

    public static Buckets bucketsFor(DateRange r) {
        BucketUnit unit = r.days() <= 31 ? BucketUnit.DAY : r.days() <= 130 ? BucketUnit.WEEK : BucketUnit.MONTH;
        List<Bucket> buckets = new ArrayList<>();

        if (unit == BucketUnit.DAY) {
            for (int i = 0; i < r.days(); i++) {
                LocalDate d = r.from().plusDays(i);
                buckets.add(new Bucket(d.toString(), d.getDayOfMonth() + " " + MONTH_SHORT[d.getMonthValue() - 1],
                        fmtDate(d), d, d, false));
            }
            return new Buckets(unit, buckets);
        }

        if (unit == BucketUnit.WEEK) {
            for (int start = 0; start < r.days(); start += 7) {
                LocalDate from = r.from().plusDays(start);
                LocalDate to = r.from().plusDays(Math.min(start + 6, r.days() - 1));
                buckets.add(new Bucket(from.toString(), from.getDayOfMonth() + " " + MONTH_SHORT[from.getMonthValue() - 1],
                        fmtDate(from) + " — " + fmtDate(to), from, to, daysBetween(from, to) < 6));
            }
            return new Buckets(unit, buckets);
        }

        int y = r.from().getYear();
        int mo = r.from().getMonthValue(); // 1-indexed
        int endY = r.to().getYear();
        int endMo = r.to().getMonthValue();
        while (y < endY || (y == endY && mo <= endMo)) {
            LocalDate first = LocalDate.of(y, mo, 1);
            LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
            LocalDate from = first.isBefore(r.from()) ? r.from() : first;
            LocalDate to = last.isAfter(r.to()) ? r.to() : last;
            buckets.add(new Bucket(y + "-" + (mo - 1), MONTH_SHORT[mo - 1], MONTH_SHORT[mo - 1] + " " + y,
                    from, to, first.isBefore(r.from()) || last.isAfter(r.to())));
            mo += 1;
            if (mo > 12) {
                mo = 1;
                y += 1;
            }
        }
        return new Buckets(unit, buckets);
    }
}

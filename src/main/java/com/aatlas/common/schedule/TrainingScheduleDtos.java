package com.aatlas.common.schedule;

import java.time.Instant;

/** The training-schedule screen's shapes, shared by every trained model. */
public final class TrainingScheduleDtos {

    private TrainingScheduleDtos() {
    }

    /**
     * What a person chooses. {@code at} is "05:30" in {@code timeZone}; {@code weekday} 1-7 for weekly,
     * {@code monthDay} 1-28 for monthly.
     *
     * @param retrainOnNewData also retrain as soon as {@code minNewRows} new rows have come in
     */
    public record Request(String frequency, String at, Integer weekday, Integer monthDay, String timeZone,
            Boolean retrainOnNewData, Integer minNewRows) {
    }

    /**
     * Where the schedule stands.
     *
     * @param summary "Every Monday at 05:00 (Europe/London)"
     * @param newRowsWaiting rows that came in since the last training run (sales or received orders)
     * @param newRowsLabel what a row is: "sales", "received orders"
     */
    public record View(String frequency, String at, Integer weekday, Integer monthDay, String timeZone,
            boolean retrainOnNewData, int minNewRows, String summary, Instant nextRunAt, Instant lastRunAt,
            String lastResult, String lastNote, long newRowsWaiting, String newRowsLabel) {
    }

    /** The schedule as stored, with what is waiting. */
    public static View view(TrainingSchedules.Schedule s, long newRowsWaiting, String newRowsLabel, Instant now) {
        Recurrence r = s.recurrence();
        Instant next = s.nextRunAt() != null ? s.nextRunAt() : r.next(now);
        return new View(r.frequency(), r.at().toString(), r.weekday(), r.monthDay(), r.zone().getId(),
                s.onNewData(), s.minNewRows(),
                r.describe() + (r.manual() ? "" : " (" + r.zone().getId() + ")"),
                next, s.lastRunAt(), s.lastResult(), s.lastNote(), newRowsWaiting, newRowsLabel);
    }

    /** Validates a request into a recurrence; {@link IllegalArgumentException} with a readable sentence when wrong. */
    public static Recurrence recurrence(Request req) {
        return Recurrence.of(req.frequency(), req.at(), req.weekday(), req.monthDay(), req.timeZone());
    }

    /** 1 to 100,000; 50 when not given. */
    public static int minNewRows(Request req) {
        int n = req.minNewRows() == null ? 50 : req.minNewRows();
        if (n < 1 || n > 100_000) {
            throw new IllegalArgumentException("Retrain after 1 to 100,000 new rows.");
        }
        return n;
    }
}

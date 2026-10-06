package com.aatlas.common.schedule;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code model_training_schedules}: when each of a tenant's trained models retrains - a
 * {@link Recurrence}, and optionally as soon as enough new rows (sales, received orders) have
 * come in since the last run. One row per tenant and model; a tenant with no row has the
 * model's default. The models' own tickers read and claim runs here; nothing in this class
 * knows what a model is.
 */
@Repository
public class TrainingSchedules {

    /** Retraining on new data waits at least this long after the last run, so a burst of imports trains once. */
    public static final java.time.Duration NEW_DATA_COOL_DOWN = java.time.Duration.ofMinutes(30);

    private final JdbcTemplate jdbc;

    TrainingSchedules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param onNewData retrain as soon as {@code minNewRows} new rows have come in since the last run
     * @param nextRunAt the next scheduled run; null for {@code manual}
     * @param lastResult {@code trained}, {@code not-trained} (too little data) or {@code failed}; null before any run
     */
    public record Schedule(Recurrence recurrence, boolean onNewData, int minNewRows, Instant nextRunAt,
            Instant lastRunAt, String lastResult, String lastNote, boolean stored) {
    }

    @Transactional(readOnly = true)
    public Schedule get(UUID tenantId, String model, Recurrence defaults) {
        List<Schedule> rows = jdbc.query("""
                select frequency, at_time, weekday, month_day, time_zone, retrain_on_new_data, min_new_rows,
                       next_run_at, last_run_at, last_result, last_note
                  from model_training_schedules where tenant_id = ? and model = ?
                """, (rs, i) -> new Schedule(
                        new Recurrence(rs.getString("frequency"), rs.getObject("at_time", LocalTime.class),
                                (Integer) rs.getObject("weekday"), (Integer) rs.getObject("month_day"),
                                zone(rs.getString("time_zone"))),
                        rs.getBoolean("retrain_on_new_data"), rs.getInt("min_new_rows"), instant(rs, "next_run_at"),
                        instant(rs, "last_run_at"), rs.getString("last_result"), rs.getString("last_note"), true),
                tenantId, model);
        return rows.isEmpty() ? new Schedule(defaults, false, 50, null, null, null, null, false) : rows.get(0);
    }

    /** Writes a person's choice; the next run is worked out from now. */
    @Transactional
    public void save(UUID tenantId, String model, Recurrence r, boolean onNewData, int minNewRows, Instant now,
            UUID userId) {
        jdbc.update("""
                insert into model_training_schedules (tenant_id, model, frequency, at_time, weekday, month_day,
                    time_zone, retrain_on_new_data, min_new_rows, next_run_at, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, model) do update
                   set frequency = excluded.frequency, at_time = excluded.at_time, weekday = excluded.weekday,
                       month_day = excluded.month_day, time_zone = excluded.time_zone,
                       retrain_on_new_data = excluded.retrain_on_new_data, min_new_rows = excluded.min_new_rows,
                       next_run_at = excluded.next_run_at, updated_by = excluded.updated_by, updated_at = now()
                """, tenantId, model, r.frequency(), r.at(), r.weekday(), r.monthDay(), r.zone().getId(), onNewData,
                minNewRows, ts(r.next(now)), userId);
    }

    /**
     * Takes a due run: sets the next run and the start of this one, only if nobody else did first.
     * A tenant with no row gets one with the default, so its runs are tracked from then on.
     */
    @Transactional
    public boolean claim(UUID tenantId, String model, Schedule s, Instant next, Instant now) {
        if (!s.stored()) {
            Recurrence r = s.recurrence();
            return jdbc.update("""
                    insert into model_training_schedules (tenant_id, model, frequency, at_time, weekday, month_day,
                        time_zone, next_run_at, last_run_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, model) do nothing
                    """, tenantId, model, r.frequency(), r.at(), r.weekday(), r.monthDay(), r.zone().getId(), ts(next),
                    ts(now)) > 0;
        }
        return jdbc.update("""
                update model_training_schedules set next_run_at = ?, last_run_at = ?
                 where tenant_id = ? and model = ? and last_run_at is not distinct from ?
                """, ts(next), ts(now), tenantId, model, ts(s.lastRunAt())) > 0;
    }

    @Transactional
    public void finished(UUID tenantId, String model, String result, String note) {
        jdbc.update("update model_training_schedules set last_result = ?, last_note = ? where tenant_id = ? and model = ?",
                result, note == null ? null : note.length() > 500 ? note.substring(0, 500) : note, tenantId, model);
    }

    /** A first run for a tenant with no row yet: the default's next time, without running now. */
    @Transactional
    public void seed(UUID tenantId, String model, Recurrence r, Instant now) {
        jdbc.update("""
                insert into model_training_schedules (tenant_id, model, frequency, at_time, weekday, month_day,
                    time_zone, next_run_at)
                values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, model) do nothing
                """, tenantId, model, r.frequency(), r.at(), r.weekday(), r.monthDay(), r.zone().getId(),
                ts(r.next(now)));
    }

    /**
     * Whether a run is due now: its scheduled time has come, or - when it retrains on new data - enough
     * new rows are waiting and the last run was long enough ago.
     *
     * @param newRows rows that came in since the last training run; ignored unless the schedule watches for them
     */
    public static Due due(Schedule s, Instant now, long newRows) {
        if (s.nextRunAt() != null && !s.nextRunAt().isAfter(now)) {
            return new Due(true, "scheduled");
        }
        if (s.onNewData() && newRows >= s.minNewRows()
                && (s.lastRunAt() == null || s.lastRunAt().plus(NEW_DATA_COOL_DOWN).isBefore(now))) {
            return new Due(true, "new-data");
        }
        return new Due(false, null);
    }

    public record Due(boolean due, String why) {
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException ex) {
            return ZoneId.of("UTC");
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

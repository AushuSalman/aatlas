package com.aatlas.competition.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code price_check_schedules}: the tenant's named competitor-price check profiles. */
@Repository
class PriceSchedules {

    private final JdbcTemplate jdbc;

    PriceSchedules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param every null for a one-time schedule (then {@code unit} is null too)
     * @param nextRunAt null once a one-time schedule has run
     */
    record Schedule(UUID id, String name, String scope, List<String> categories, List<String> items,
            List<String> sources, Instant startsAt, Integer every, String unit, String timeZone, boolean active,
            Instant nextRunAt, Instant lastRunAt, Instant createdAt) {
    }

    /** What a create or an edit writes. */
    record Fields(String name, String scope, List<String> categories, List<String> items, List<String> sources,
            Instant startsAt, Integer every, String unit, String timeZone, boolean active, Instant nextRunAt) {
    }

    private static final String COLUMNS = "id, name, scope, categories, items, sources, starts_at, repeat_every, "
            + "repeat_unit, time_zone, active, next_run_at, last_run_at, created_at";

    private static final RowMapper<Schedule> ROW = (rs, i) -> new Schedule(rs.getObject("id", UUID.class),
            rs.getString("name"), rs.getString("scope"), strings(rs.getArray("categories")),
            strings(rs.getArray("items")), strings(rs.getArray("sources")), instant(rs, "starts_at"),
            (Integer) rs.getObject("repeat_every"), rs.getString("repeat_unit"), rs.getString("time_zone"),
            rs.getBoolean("active"), instant(rs, "next_run_at"), instant(rs, "last_run_at"),
            instant(rs, "created_at"));

    @Transactional(readOnly = true)
    List<Schedule> list(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from price_check_schedules where tenant_id = ? order by created_at",
                ROW, tenantId);
    }

    @Transactional(readOnly = true)
    Schedule get(UUID tenantId, UUID id) {
        List<Schedule> rows = jdbc.query("select " + COLUMNS + " from price_check_schedules where tenant_id = ? and id = ?",
                ROW, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    int count(UUID tenantId) {
        Integer n = jdbc.queryForObject("select count(*) from price_check_schedules where tenant_id = ?", Integer.class,
                tenantId);
        return n == null ? 0 : n;
    }

    /** Another schedule of the tenant already carries this name (case aside). */
    @Transactional(readOnly = true)
    boolean nameTaken(UUID tenantId, String name, UUID except) {
        Boolean taken = jdbc.queryForObject("select exists (select 1 from price_check_schedules where tenant_id = ? "
                + "and lower(name) = lower(?) and (cast(? as uuid) is null or id <> ?))", Boolean.class, tenantId, name,
                except, except);
        return Boolean.TRUE.equals(taken);
    }

    @Transactional
    UUID create(UUID tenantId, Fields f, UUID userId) {
        return jdbc.queryForObject("""
                insert into price_check_schedules (tenant_id, name, scope, categories, items, sources, starts_at,
                                                   repeat_every, repeat_unit, time_zone, active, next_run_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, f.name(), f.scope(), arr(f.categories()), arr(f.items()), arr(f.sources()),
                ts(f.startsAt()), f.every(), f.unit(), f.timeZone(), f.active(), ts(f.nextRunAt()), userId);
    }

    @Transactional
    boolean update(UUID tenantId, UUID id, Fields f) {
        return jdbc.update("""
                update price_check_schedules
                   set name = ?, scope = ?, categories = ?, items = ?, sources = ?, starts_at = ?, repeat_every = ?,
                       repeat_unit = ?, time_zone = ?, active = ?, next_run_at = ?, version = version + 1
                 where tenant_id = ? and id = ?
                """, f.name(), f.scope(), arr(f.categories()), arr(f.items()), arr(f.sources()), ts(f.startsAt()),
                f.every(), f.unit(), f.timeZone(), f.active(), ts(f.nextRunAt()), tenantId, id) > 0;
    }

    @Transactional
    boolean delete(UUID tenantId, UUID id) {
        return jdbc.update("delete from price_check_schedules where tenant_id = ? and id = ?", tenantId, id) > 0;
    }

    /** Active schedules whose next run has come. */
    @Transactional(readOnly = true)
    List<Schedule> due(UUID tenantId, Instant now) {
        return jdbc.query("select " + COLUMNS + " from price_check_schedules where tenant_id = ? and active "
                + "and next_run_at is not null and next_run_at <= ? order by next_run_at", ROW, tenantId, ts(now));
    }

    /**
     * Takes a due run: moves the schedule on to {@code next} only if nobody else did first (another
     * pod, or a click), so each run starts once.
     */
    @Transactional
    boolean claim(UUID tenantId, UUID id, Instant expected, Instant next, Instant ranAt) {
        return jdbc.update("update price_check_schedules set next_run_at = ?, last_run_at = ? "
                + "where tenant_id = ? and id = ? and active and next_run_at = ?", ts(next), ts(ranAt), tenantId, id,
                ts(expected)) > 0;
    }

    @Transactional
    void ranAt(UUID tenantId, UUID id, Instant at) {
        jdbc.update("update price_check_schedules set last_run_at = ? where tenant_id = ? and id = ?", ts(at), tenantId,
                id);
    }

    @Transactional(readOnly = true)
    String name(UUID tenantId, UUID id) {
        List<String> rows = jdbc.queryForList("select name from price_check_schedules where tenant_id = ? and id = ?",
                String.class, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> strings(java.sql.Array a) throws java.sql.SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static String[] arr(List<String> l) {
        return l == null ? new String[0] : l.toArray(String[]::new);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

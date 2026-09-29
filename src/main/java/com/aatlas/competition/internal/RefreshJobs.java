package com.aatlas.competition.internal;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code competitor_refresh_jobs}: background competitor-price runs and their progress. */
@Repository
class RefreshJobs {

    private final JdbcTemplate jdbc;

    RefreshJobs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Job(UUID id, String trigger, String status, List<String> sources, List<String> items, int total, int done,
            int priced, int observations, int failed, String error, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private static final RowMapper<Job> JOB = (rs, i) -> new Job(rs.getObject("id", UUID.class),
            rs.getString("trigger"), rs.getString("status"),
            Arrays.asList((String[]) rs.getArray("sources").getArray()),
            Arrays.asList((String[]) rs.getArray("items").getArray()), rs.getInt("total"), rs.getInt("done"),
            rs.getInt("priced"), rs.getInt("observations"), rs.getInt("failed"), rs.getString("error"),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private static final String COLUMNS = "id, trigger, status, sources, items, total, done, priced, observations, "
            + "failed, error, started_at, finished_at, created_at, updated_at";

    @Transactional
    UUID create(UUID tenantId, String trigger, List<String> sources, List<String> items, UUID userId) {
        return jdbc.queryForObject("""
                insert into competitor_refresh_jobs (tenant_id, trigger, sources, items, total, created_by)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, trigger, sources.toArray(String[]::new), items.toArray(String[]::new),
                items.size(), userId);
    }

    @Transactional(readOnly = true)
    Job get(UUID tenantId, UUID id) {
        List<Job> rows = jdbc.query("select " + COLUMNS + " from competitor_refresh_jobs where tenant_id = ? and id = ?",
                JOB, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    Job latest(UUID tenantId) {
        List<Job> rows = jdbc.query("select " + COLUMNS
                + " from competitor_refresh_jobs where tenant_id = ? order by created_at desc limit 1", JOB, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    void started(UUID tenantId, UUID id) {
        jdbc.update("update competitor_refresh_jobs set status = 'running', started_at = now() "
                + "where tenant_id = ? and id = ?", tenantId, id);
    }

    @Transactional
    void progress(UUID tenantId, UUID id, int done, int priced, int observations, int failed) {
        jdbc.update("update competitor_refresh_jobs set done = ?, priced = ?, observations = ?, failed = ? "
                + "where tenant_id = ? and id = ?", done, priced, observations, failed, tenantId, id);
    }

    @Transactional
    void finished(UUID tenantId, UUID id, String status, String error) {
        jdbc.update("update competitor_refresh_jobs set status = ?, error = ?, finished_at = now() "
                + "where tenant_id = ? and id = ?", status, error, tenantId, id);
    }

    /** The item numbers an import batch created or updated. */
    @Transactional(readOnly = true)
    List<String> itemsOfBatch(UUID tenantId, UUID batchId) {
        return jdbc.queryForList("select item_number from products where tenant_id = ? and import_batch_id = ? "
                + "order by item_number", String.class, tenantId, batchId);
    }
}

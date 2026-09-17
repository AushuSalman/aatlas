package com.aatlas.ingest.internal;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant's one {@code csv} data-source row: the server-side record that imports have
 * been loaded, which is what opens the workspace for a tenant who never connected anything
 * else. Written after every upload commit, refreshed after every rollback, removed when no
 * committed upload remains.
 */
@Component
class CsvSourceRecorder {

    static final String LABEL = "CSV imports";

    private final JdbcTemplate jdbc;

    CsvSourceRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or updates the row and returns its id. */
    UUID recordAfterCommit(UUID tenantId, UUID actorOrNull) {
        String detail = detail(tenantId);
        return jdbc.query("""
                insert into data_sources (tenant_id, kind, label, detail, status, connected_at, last_sync_at, connected_by)
                values (?, 'csv', ?, ?, 'connected', now(), now(), ?)
                on conflict (tenant_id) where kind = 'csv'
                do update set detail = excluded.detail, last_sync_at = now(), updated_at = now()
                returning id
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setString(2, LABEL);
            ps.setString(3, detail == null ? "No imports loaded" : detail);
            ps.setObject(4, actorOrNull, Types.OTHER);
        }, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
    }

    /** Restates the detail, or removes the row when nothing committed is left. */
    void refreshAfterRollback(UUID tenantId) {
        String detail = detail(tenantId);
        if (detail == null) {
            jdbc.update("delete from data_sources where tenant_id = ? and kind = 'csv'", tenantId);
            return;
        }
        jdbc.update("update data_sources set detail = ?, last_sync_at = now() where tenant_id = ? and kind = 'csv'",
                detail, tenantId);
    }

    /** {@code "11,858 sales rows · 26 months, 812 purchase lines, 17 products priced, 64 competitor prices"}. */
    String detail(UUID tenantId) {
        List<Map<String, Object>> perKind = jdbc.queryForList("""
                select kind, coalesce(sum(loaded_rows), 0) as rows
                  from import_batches
                 where tenant_id = ? and source = 'upload' and status = 'COMMITTED'
                 group by kind
                """, tenantId);
        if (perKind.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> row : perKind) {
            long rows = ((Number) row.get("rows")).longValue();
            switch ((String) row.get("kind")) {
                case "sales" -> {
                    Integer months = jdbc.queryForObject("""
                            select count(distinct date_trunc('month', txn_date))
                              from sales_transactions where tenant_id = ? and source = 'import'
                            """, Integer.class, tenantId);
                    parts.add(String.format("%,d sales rows · %d months", rows, months == null ? 0 : months));
                }
                case "purchases" -> parts.add(String.format("%,d purchase lines", rows));
                case "products" -> {
                    Integer priced = jdbc.queryForObject("""
                            select count(distinct product_id) from product_prices
                             where tenant_id = ? and source = 'import'
                            """, Integer.class, tenantId);
                    parts.add(String.format("%,d products priced", priced == null ? 0 : priced));
                }
                case "competitor_prices" -> parts.add(String.format("%,d competitor prices", rows));
                default -> parts.add(String.format("%,d rows", rows));
            }
        }
        return String.join(", ", parts);
    }
}

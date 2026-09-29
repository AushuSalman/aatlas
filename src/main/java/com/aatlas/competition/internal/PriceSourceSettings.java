package com.aatlas.competition.internal;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code competition_settings}: which live price sources a tenant has switched on. */
@Repository
class PriceSourceSettings {

    private final JdbcTemplate jdbc;

    PriceSourceSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param configuredAt null until the tenant has chosen for the first time */
    record Settings(List<String> enabled, OffsetDateTime configuredAt) {

        boolean configured() {
            return configuredAt != null;
        }
    }

    @Transactional(readOnly = true)
    Settings get(UUID tenantId) {
        List<Settings> rows = jdbc.query(
                "select enabled_sources, configured_at from competition_settings where tenant_id = ?",
                (rs, i) -> new Settings(Arrays.asList((String[]) rs.getArray("enabled_sources").getArray()),
                        rs.getObject("configured_at", OffsetDateTime.class)),
                tenantId);
        return rows.isEmpty() ? new Settings(List.of(), null) : rows.get(0);
    }

    @Transactional
    Settings save(UUID tenantId, List<String> enabled, UUID userId) {
        jdbc.update("""
                insert into competition_settings (tenant_id, enabled_sources, configured_at, updated_by)
                values (?, ?, now(), ?)
                on conflict (tenant_id) do update
                    set enabled_sources = excluded.enabled_sources, updated_by = excluded.updated_by,
                        configured_at = coalesce(competition_settings.configured_at, excluded.configured_at),
                        updated_at = now()
                """, tenantId, enabled.toArray(String[]::new), userId);
        return get(tenantId);
    }
}

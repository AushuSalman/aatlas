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

    /**
     * @param configuredAt null until the tenant has chosen for the first time
     * @param dailyRefresh re-check every product once a day with the enabled sources
     */
    record Settings(List<String> enabled, OffsetDateTime configuredAt, boolean dailyRefresh) {

        boolean configured() {
            return configuredAt != null;
        }
    }

    @Transactional(readOnly = true)
    Settings get(UUID tenantId) {
        List<Settings> rows = jdbc.query(
                "select enabled_sources, configured_at, daily_refresh from competition_settings where tenant_id = ?",
                (rs, i) -> new Settings(Arrays.asList((String[]) rs.getArray("enabled_sources").getArray()),
                        rs.getObject("configured_at", OffsetDateTime.class), rs.getBoolean("daily_refresh")),
                tenantId);
        return rows.isEmpty() ? new Settings(List.of(), null, true) : rows.get(0);
    }

    /** @param dailyRefresh null keeps what is stored (on for a new row) */
    @Transactional
    Settings save(UUID tenantId, List<String> enabled, Boolean dailyRefresh, UUID userId) {
        jdbc.update("""
                insert into competition_settings (tenant_id, enabled_sources, configured_at, updated_by, daily_refresh)
                values (?, ?, now(), ?, coalesce(?, true))
                on conflict (tenant_id) do update
                    set enabled_sources = excluded.enabled_sources, updated_by = excluded.updated_by,
                        configured_at = coalesce(competition_settings.configured_at, excluded.configured_at),
                        daily_refresh = coalesce(?, competition_settings.daily_refresh),
                        updated_at = now()
                """, tenantId, enabled.toArray(String[]::new), userId, dailyRefresh, dailyRefresh);
        return get(tenantId);
    }
}

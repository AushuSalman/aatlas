package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Gives existing sample tenants real history on the first boot after V22.
 *
 * <p>V22 deleted the seeded procurement ledger and the seeded deals - hashes presented as
 * facts - so a demo account that connected the sample before this release has a catalogue
 * and nothing else. On boot, every tenant with a sample data source and no sample batches
 * is loaded through {@link SampleHistoryLoader}; the claim index makes this safe across pods
 * and restarts, and a tenant already loaded is skipped by the batch check.
 */
@Component
class SampleHistoryBackfill {

    private static final Logger log = LoggerFactory.getLogger(SampleHistoryBackfill.class);

    private final JdbcTemplate jdbc;
    private final DataSourceRepository sources;
    private final ImportBatchRepository batches;
    private final SampleHistoryLoader loader;

    SampleHistoryBackfill(JdbcTemplate jdbc, DataSourceRepository sources, ImportBatchRepository batches,
            SampleHistoryLoader loader) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.batches = batches;
        this.loader = loader;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        List<UUID> tenants;
        try {
            tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        } catch (RuntimeException ex) {
            log.warn("Sample history backfill could not list tenants", ex);
            return;
        }
        int started = 0;
        for (UUID tenantId : tenants) {
            try {
                boolean due = TenantContext.runAs(TenantContext.Actor.system(tenantId), () ->
                        sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE).isPresent()
                                && !batches.existsByTenantIdAndSource(tenantId, "sample"));
                if (due) {
                    log.info("Backfilling sample history for tenant {}", tenantId);
                    loader.loadAll(tenantId);
                    started++;
                }
            } catch (RuntimeException ex) {
                log.error("Sample history backfill failed for tenant {}", tenantId, ex);
            }
        }
        if (started > 0) {
            log.info("Sample history backfill loaded {} tenant(s)", started);
        }
    }
}

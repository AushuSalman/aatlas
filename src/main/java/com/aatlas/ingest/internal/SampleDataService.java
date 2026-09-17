package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.ingest.SampleDataRemoved;
import com.aatlas.ingest.internal.csv.ImportKind;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sample's lifecycle after connect: reload what is missing, or remove it all.
 *
 * <p>Removal is the exit from the demo: every sample batch rolled back (competitor prices,
 * purchases, products, sales - reverse of the load order), then every {@code source='sample'}
 * customer, supplier, product and branch that nothing references, then the source row. The
 * workspace is back at onboarding and nothing Hardin is left to be mistaken for the
 * tenant's own figures.
 */
@Service
class SampleDataService {

    private static final Logger log = LoggerFactory.getLogger(SampleDataService.class);

    /** Reverse of the load order: facts before the catalogue rows they reference. */
    private static final List<ImportKind> REMOVAL_ORDER = List.of(
            ImportKind.COMPETITOR_PRICES, ImportKind.PURCHASES, ImportKind.PRODUCTS, ImportKind.SALES);

    record SampleBatchStatus(String kind, String status) {
    }

    record ReloadResponse(List<SampleBatchStatus> batches) {
    }

    private final DataSourceRepository sources;
    private final ImportBatchRepository batches;
    private final ImportIssueRepository issues;
    private final ImportFileStore files;
    private final ImportRollback rollback;
    private final SampleHistoryLoader loader;
    private final SampleHistoryRunner runner;
    private final JdbcTemplate jdbc;
    private final DomainEventPublisher events;
    private final HistoryCaches caches;
    private final AatlasClock clock;

    SampleDataService(
            DataSourceRepository sources,
            ImportBatchRepository batches,
            ImportIssueRepository issues,
            ImportFileStore files,
            ImportRollback rollback,
            SampleHistoryLoader loader,
            SampleHistoryRunner runner,
            JdbcTemplate jdbc,
            DomainEventPublisher events,
            HistoryCaches caches,
            AatlasClock clock) {
        this.sources = sources;
        this.batches = batches;
        this.issues = issues;
        this.files = files;
        this.rollback = rollback;
        this.loader = loader;
        this.runner = runner;
        this.jdbc = jdbc;
        this.events = events;
        this.caches = caches;
        this.clock = clock;
    }

    /**
     * Claims the missing sample batches now (each in its own transaction, so readiness sees
     * them at once) and loads them in the background.
     */
    ReloadResponse reload() {
        UUID tenantId = TenantContext.requireTenantId();
        TenantContext.Actor actor = TenantContext.current().orElseThrow();
        requireSample(tenantId);

        List<UUID> claimed = loader.claimAll(tenantId);
        if (!claimed.isEmpty()) {
            runner.load(actor, claimed);
        }
        return new ReloadResponse(batches.findByTenantIdAndSourceOrderByCreatedAtAsc(tenantId, "sample").stream()
                .filter(b -> b.getStatus() != ImportBatchStatus.ROLLED_BACK)
                .map(b -> new SampleBatchStatus(b.getKind(), b.getStatus().name()))
                .toList());
    }

    @Transactional
    void remove() {
        UUID tenantId = TenantContext.requireTenantId();
        DataSourceEntity source = requireSample(tenantId);
        if (batches.existsByTenantIdAndSourceAndStatus(tenantId, "sample", ImportBatchStatus.COMMITTING)) {
            throw ApiException.conflict("sample_loading",
                    "The sample is still loading. Try again in a few seconds.");
        }

        List<ImportBatchEntity> sampleBatches = batches.findByTenantIdAndSourceOrderByCreatedAtAsc(tenantId, "sample");
        for (ImportKind kind : REMOVAL_ORDER) {
            for (ImportBatchEntity batch : sampleBatches) {
                if (batch.kind() != kind) {
                    continue;
                }
                if (batch.getStatus() == ImportBatchStatus.COMMITTED) {
                    rollback.rollback(batch);
                }
                issues.deleteByBatch(tenantId, batch.getId());
                batches.delete(batch);
                files.delete(batch.getFileKey());
            }
        }
        batches.flush();

        jdbc.update("delete from customers where tenant_id = ? and source = 'sample'", tenantId);
        // The seeded panel is source='sample' (V22 backfill) or, for a panel seeded through the
        // entity since, an untagged 'sup-%' key; both are the sample's.
        jdbc.update("""
                delete from suppliers s
                 where s.tenant_id = ?
                   and (s.source = 'sample' or (s.source is null and s.supplier_key like 'sup-%'))
                   and not exists (select 1 from purchase_order po
                                    where po.tenant_id = s.tenant_id and po.supplier_id = s.supplier_key)
                """, tenantId);
        jdbc.update("""
                delete from products p
                 where p.tenant_id = ? and p.source = 'sample'
                   and not exists (select 1 from sales_transactions x where x.tenant_id = p.tenant_id and x.product_id = p.id)
                   and not exists (select 1 from purchase_order x where x.tenant_id = p.tenant_id and x.product_id = p.id)
                   and not exists (select 1 from product_prices x where x.product_id = p.id)
                   and not exists (select 1 from inventory_positions x where x.product_id = p.id)
                   and not exists (select 1 from competitor_prices x where x.product_id = p.id)
                   and not exists (select 1 from supplier_products sp where sp.product_id = p.id and sp.ex_works is not null)
                """, tenantId);
        jdbc.update("""
                delete from stores s
                 where s.tenant_id = ? and s.source = 'sample'
                   and not exists (select 1 from sales_transactions x where x.tenant_id = s.tenant_id and x.store_id = s.id)
                   and not exists (select 1 from product_prices x where x.store_id = s.id)
                   and not exists (select 1 from inventory_positions x where x.store_id = s.id)
                   and not exists (select 1 from competitor_prices x where x.store_id = s.id)
                   and not exists (select 1 from purchase_order x
                                    where x.tenant_id = s.tenant_id and (x.store_id = s.id or x.branch_id = s.store_code))
                """, tenantId);

        sources.delete(source);
        events.publish(new SampleDataRemoved(tenantId, clock.now()));
        caches.evictAfterCommit(tenantId);
        log.info("Sample data removed from tenant {}", tenantId);
    }

    private DataSourceEntity requireSample(UUID tenantId) {
        return sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "no_sample",
                        "This workspace does not run on the sample dataset."));
    }
}

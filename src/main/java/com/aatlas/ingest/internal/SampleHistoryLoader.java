package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.ingest.SampleDataConnected;
import com.aatlas.ingest.internal.csv.ImportKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the Hardin sample's history through the real import path once the sample catalogue
 * is connected.
 *
 * <p>Four batches, in the order products (categories and costs first) → sales → purchases
 * (after sales, so the branches exist) → competitor prices (last, regions). Each is
 * <b>claimed</b> in a transaction of its own - a batch row in {@code COMMITTING} guarded by
 * {@code import_batches_sample_kind_uk}, so a republished event or a second pod hits the
 * index and skips - and every claim lands before any load, so readiness lists all four rows
 * from the first poll. Each load then runs in its own transaction
 * ({@link ImportCommitter#loadIsolated}); a kind that fails is marked failed on its own and
 * the others still land.
 *
 * <p>The listener joins Modulith's REQUIRES_NEW transaction, which is exactly why nothing
 * that must survive a failed kind runs inside it directly.
 */
@Component
class SampleHistoryLoader {

    private static final Logger log = LoggerFactory.getLogger(SampleHistoryLoader.class);

    static final List<ImportKind> ORDER = List.of(
            ImportKind.PRODUCTS, ImportKind.SALES, ImportKind.PURCHASES, ImportKind.COMPETITOR_PRICES);

    private final ImportBatchRepository batches;
    private final ImportIssueRepository issues;
    private final ImportFileStore files;
    private final DataSourceRepository sources;
    private final ImportService imports;
    private final ImportCommitter committer;
    private final SampleFiles samples;
    private final HistoryCaches caches;
    private final AatlasClock clock;
    private final TransactionTemplate requiresNew;

    SampleHistoryLoader(
            ImportBatchRepository batches,
            ImportIssueRepository issues,
            ImportFileStore files,
            DataSourceRepository sources,
            ImportService imports,
            ImportCommitter committer,
            SampleFiles samples,
            HistoryCaches caches,
            AatlasClock clock,
            PlatformTransactionManager transactions) {
        this.batches = batches;
        this.issues = issues;
        this.files = files;
        this.sources = sources;
        this.imports = imports;
        this.committer = committer;
        this.samples = samples;
        this.caches = caches;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @ApplicationModuleListener
    public void on(SampleDataConnected event) {
        loadAll(event.tenantId());
    }

    /** Claims and loads every kind the tenant does not have yet, as the system actor. */
    void loadAll(UUID tenantId) {
        TenantContext.runAs(TenantContext.Actor.system(tenantId), () -> loadClaimed(tenantId, claimAll(tenantId)));
    }

    /**
     * One batch row per kind lacking a live one, each in its own transaction, all before any
     * load. Must run inside a {@code TenantContext}.
     */
    List<UUID> claimAll(UUID tenantId) {
        List<UUID> claimed = new ArrayList<>();
        for (ImportKind kind : ORDER) {
            try {
                requiresNew.execute(status -> claim(tenantId, kind)).ifPresent(claimed::add);
            } catch (DataIntegrityViolationException ex) {
                // Already claimed - a republished event, a second pod, or the boot backfill
                // racing the listener. The index is the arbiter; nothing to do.
                log.debug("Sample {} for tenant {} already claimed", kind.key(), tenantId, ex);
            }
        }
        if (!claimed.isEmpty()) {
            caches.evictAfterCommit(tenantId);
        }
        return claimed;
    }

    /** Loads the claimed batches in order; a failure marks its own batch and moves on. */
    void loadClaimed(UUID tenantId, List<UUID> batchIds) {
        for (UUID batchId : batchIds) {
            String kind = batches.findById(batchId).map(ImportBatchEntity::getKind).orElse("history");
            try {
                committer.loadIsolated(tenantId, batchId);
            } catch (RuntimeException ex) {
                log.error("Sample {} for tenant {} could not be loaded", kind, tenantId, ex);
                try {
                    committer.markFailed(tenantId, batchId, "The sample " + kind + " could not be loaded.");
                } catch (RuntimeException nested) {
                    log.error("Sample {} for tenant {} failed and its failure could not be recorded", kind, tenantId,
                            nested);
                }
            }
        }
    }

    private Optional<UUID> claim(UUID tenantId, ImportKind kind) {
        if (batches.existsByTenantIdAndKindAndSourceAndStatusIn(tenantId, kind.key(), "sample",
                List.of(ImportBatchStatus.COMMITTED, ImportBatchStatus.COMMITTING))) {
            return Optional.empty();
        }
        // A failed or never-committed sample row for this kind would block the claim index;
        // it is superseded by this claim.
        for (ImportBatchEntity stale : batches.findByTenantIdAndKindAndSource(tenantId, kind.key(), "sample")) {
            if (stale.getStatus() != ImportBatchStatus.ROLLED_BACK) {
                issues.deleteByBatch(tenantId, stale.getId());
                batches.delete(stale);
                files.delete(stale.getFileKey());
            }
        }
        batches.flush();

        UUID sourceId = sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE)
                .map(DataSourceEntity::getId)
                .orElse(null);
        String fileName = kind.sampleFile().substring(kind.sampleFile().lastIndexOf('/') + 1);
        ImportBatchEntity batch = imports.createBatch(kind, "sample", fileName, samples.bytes(kind), sourceId);
        if (!batch.committable()) {
            batch.markFailed("sample_invalid: the " + kind.key() + " sample did not validate ("
                    + batch.getAcceptedRows() + " of " + batch.getTotalRows() + " rows accepted)");
            batches.save(batch);
            log.error("Sample {} for tenant {} did not validate", kind.key(), tenantId);
            return Optional.empty();
        }
        batch.markCommitting();
        batches.saveAndFlush(batch);
        sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE).ifPresent(source -> {
            source.touchSync(clock.now());
            sources.save(source);
        });
        return Optional.of(batch.getId());
    }
}

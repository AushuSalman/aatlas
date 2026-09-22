package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.ingest.ImportCommitted;
import com.aatlas.ingest.internal.csv.AcceptedRow;
import com.aatlas.ingest.internal.csv.ColumnMapping;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ValidationContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a commit.
 *
 * <p>Separate from {@link ImportCommitRunner} so that {@code @Async} and
 * {@code @Transactional} sit on different beans and reach each other through a proxy.
 * Stacked on one method they work by accident of advisor ordering; split like this they work
 * because of how Spring is built.
 *
 * <p>Two entry points with one body: {@link #load} joins the caller's transaction (the async
 * runner has none, so it opens one); {@link #loadIsolated} always opens its own, which is what
 * the sample loader needs so one failed kind cannot take the other three with it.
 */
@Component
class ImportCommitter {

    private static final Logger log = LoggerFactory.getLogger(ImportCommitter.class);

    private final ImportBatchRepository batches;
    private final ImportFileStore files;
    private final ImportKinds kinds;
    private final ValidationContexts contexts;
    private final CsvSourceRecorder csvSource;
    private final DomainEventPublisher events;
    private final HistoryCaches caches;
    private final JdbcTemplate jdbc;
    private final AatlasClock clock;

    ImportCommitter(
            ImportBatchRepository batches,
            ImportFileStore files,
            ImportKinds kinds,
            ValidationContexts contexts,
            CsvSourceRecorder csvSource,
            DomainEventPublisher events,
            HistoryCaches caches,
            JdbcTemplate jdbc,
            AatlasClock clock) {
        this.batches = batches;
        this.files = files;
        this.kinds = kinds;
        this.contexts = contexts;
        this.csvSource = csvSource;
        this.events = events;
        this.caches = caches;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Loads every accepted row, in one transaction.
     *
     * <p>All or nothing on purpose. A partial import is the worst outcome available: the
     * tenant cannot tell which months are complete, and the pricing engine would draw
     * confident conclusions from half a year. If anything fails, nothing landed and the
     * batch can simply be committed again.
     */
    @Transactional
    void load(UUID tenantId, UUID batchId) {
        doLoad(tenantId, batchId);
    }

    /** {@link #load}, in a transaction of its own whatever the caller holds. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void loadIsolated(UUID tenantId, UUID batchId) {
        doLoad(tenantId, batchId);
    }

    private void doLoad(UUID tenantId, UUID batchId) {
        ImportBatchEntity batch = batches.findByTenantIdAndId(tenantId, batchId)
                .orElseThrow(() -> ApiException.notFound("Import", batchId));
        ImportKind kind = batch.kind();
        ImportKinds.Entry<?> entry = kinds.entry(kind);

        ColumnMapping mapping = ImportService.toMapping(kind, batch.getMapping());
        String csv = files.readAsString(batch.getFileKey());

        boolean sample = batch.isSampleSource();
        ValidationContext validateCtx = contexts.forBatch(tenantId, kind, batch.getSource());
        ValidationContext loadCtx = sample ? contexts.forLoad(tenantId, kind) : validateCtx;
        int shift = sample ? SampleDates.offsetDays(clock.today()) : 0;
        BigDecimal fx = sample ? sampleFx(loadCtx.tenantCurrency()) : BigDecimal.ONE;

        LoadResult result = run(entry, tenantId, batchId, batch.getSource(), csv, mapping, validateCtx, loadCtx,
                sample, shift, fx);

        batch.markCommitted(clock.now(), result, shift);
        // Flushed: the source recorder counts committed batches over JDBC, which does not see
        // a pending JPA update - without this a first import records "No imports loaded".
        batches.saveAndFlush(batch);

        if (!sample) {
            UUID sourceId = csvSource.recordAfterCommit(tenantId, batch.getUploadedBy());
            if (batch.getDataSourceId() == null) {
                batch.setDataSourceId(sourceId);
                batches.save(batch);
            }
        }

        // In the same transaction as the rows, through the outbox: the engines must not be
        // told about history that a rollback then took away.
        events.publish(new ImportCommitted(tenantId, batchId, kind.key(), batch.getSource(), result.loadedRows(),
                batch.getEarliest(), batch.getLatest(), clock.now()));
        caches.evictAfterCommit(tenantId);

        log.info("Import {} ({}, {}) committed: {} rows loaded, {} products and {} branches created, shift {} days",
                batchId, kind.key(), batch.getSource(), result.loadedRows(), result.productsCreated(),
                result.branchesCreated(), shift);
    }

    @SuppressWarnings("unchecked")
    private <R extends AcceptedRow> LoadResult run(ImportKinds.Entry<R> entry, UUID tenantId, UUID batchId,
            String source, String csv, ColumnMapping mapping, ValidationContext validateCtx,
            ValidationContext loadCtx, boolean sample, int shift, BigDecimal fx) {
        KindLoader.Load<R> load = entry.loader().begin(tenantId, batchId, source, loadCtx);
        entry.validator().forEachAcceptedRow(csv, mapping, validateCtx,
                row -> load.add(sample ? (R) row.forSample(shift, fx) : row));
        return load.finish();
    }

    /** The reference USD rate into the tenant's currency; 1 for a dollar tenant or an unknown pair. */
    private BigDecimal sampleFx(String currency) {
        if (ValidationContexts.SAMPLE_CURRENCY.equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }
        List<BigDecimal> rates = jdbc.queryForList(
                "select rate from fx_rate where base = 'USD' and quote = ? order by as_of desc limit 1",
                BigDecimal.class, currency);
        return rates.isEmpty() ? BigDecimal.ONE : rates.getFirst();
    }

    /**
     * Records why a load stopped.
     *
     * <p>{@link Propagation#REQUIRES_NEW} because the transaction that failed is being rolled
     * back, and a status written inside it would roll back with it - leaving the batch stuck
     * at {@code COMMITTING} for ever with nothing to say why.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markFailed(UUID tenantId, UUID batchId, String reason) {
        batches.findByTenantIdAndId(tenantId, batchId).ifPresent(batch -> {
            batch.markFailed(reason);
            batches.save(batch);
        });
        caches.evictAfterCommit(tenantId);
    }
}

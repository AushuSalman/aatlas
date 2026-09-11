package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.ingest.ImportCommitted;
import com.aatlas.ingest.internal.csv.ColumnMapping;
import com.aatlas.ingest.internal.csv.ImportField;
import com.aatlas.ingest.internal.csv.ImportValidator;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
class ImportCommitter {

    private static final Logger log = LoggerFactory.getLogger(ImportCommitter.class);

    private final ImportBatchRepository batches;
    private final ImportFileStore files;
    private final ImportValidator validator;
    private final SalesTransactionLoader loader;
    private final DomainEventPublisher events;
    private final AatlasClock clock;

    ImportCommitter(
            ImportBatchRepository batches,
            ImportFileStore files,
            ImportValidator validator,
            SalesTransactionLoader loader,
            DomainEventPublisher events,
            AatlasClock clock) {
        this.batches = batches;
        this.files = files;
        this.validator = validator;
        this.loader = loader;
        this.events = events;
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
        ImportBatchEntity batch = batches.findByTenantIdAndId(tenantId, batchId)
                .orElseThrow(() -> ApiException.notFound("Import", batchId));

        ColumnMapping mapping = toMapping(batch.getMapping());
        String csv = files.readAsString(batch.getFileKey());

        SalesTransactionLoader.Load load = loader.begin(tenantId, batchId);
        validator.forEachAcceptedRow(csv, mapping, load::add);
        SalesTransactionLoader.LoadResult result = load.finish();

        batch.markCommitted(
                clock.now(),
                result.loadedRows(),
                result.productsCreated(),
                result.branchesCreated(),
                result.branchesNeedingRegion(),
                result.unresolvedCustomers());
        batches.save(batch);

        // In the same transaction as the rows, through the outbox: the engines must not be
        // told about history that a rollback then took away.
        events.publish(new ImportCommitted(
                tenantId, batchId, result.loadedRows(), batch.getEarliest(), batch.getLatest(), clock.now()));

        log.info("Import {} committed: {} rows loaded, {} products and {} branches created, {} customers unmatched",
                batchId, result.loadedRows(), result.productsCreated(),
                result.branchesCreated(), result.unresolvedCustomers().size());
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
    }

    private static ColumnMapping toMapping(Map<String, Integer> stored) {
        Map<ImportField, Integer> columns = new EnumMap<>(ImportField.class);
        stored.forEach((key, index) -> {
            if (index != null) {
                columns.put(ImportField.from(key), index);
            }
        });
        return new ColumnMapping(columns);
    }
}

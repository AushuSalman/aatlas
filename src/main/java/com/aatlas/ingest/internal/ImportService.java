package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.ingest.ImportRolledBack;
import com.aatlas.ingest.internal.ImportBatchView.ImportIssueView;
import com.aatlas.ingest.internal.csv.AcceptedRow;
import com.aatlas.ingest.internal.csv.ColumnMapping;
import com.aatlas.ingest.internal.csv.ImportFieldSpec;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ImportReport;
import com.aatlas.ingest.internal.csv.KindValidator;
import com.aatlas.ingest.internal.csv.ValidationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading, revalidating, committing and rolling back a CSV of any kind.
 *
 * <p>Validation is synchronous and the commit is not, which is the split that matters.
 * Reading a file and checking it is milliseconds per megabyte - the browser already does it
 * while the user watches - and the user is waiting for that report, so making them poll for
 * it would be worse. Loading rows is unbounded work against the database, and nobody waits
 * for that: the commit returns immediately and the batch row is the job record.
 */
@Service
class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** The issues returned inline with a report. The rest come from the issues endpoint. */
    private static final int INLINE_ISSUES = 50;

    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    private final ImportBatchRepository batches;
    private final ImportIssueRepository issues;
    private final ImportFileStore files;
    private final ImportKinds kinds;
    private final ValidationContexts contexts;
    private final ImportCommitRunner commitRunner;
    private final ImportRollback rollback;
    private final DataSourceRepository sources;
    private final SampleFiles samples;
    private final CsvSourceRecorder csvSource;
    private final DomainEventPublisher events;
    private final HistoryCaches caches;
    private final AatlasClock clock;

    ImportService(
            ImportBatchRepository batches,
            ImportIssueRepository issues,
            ImportFileStore files,
            ImportKinds kinds,
            ValidationContexts contexts,
            ImportCommitRunner commitRunner,
            ImportRollback rollback,
            DataSourceRepository sources,
            SampleFiles samples,
            CsvSourceRecorder csvSource,
            DomainEventPublisher events,
            HistoryCaches caches,
            AatlasClock clock) {
        this.batches = batches;
        this.issues = issues;
        this.files = files;
        this.kinds = kinds;
        this.contexts = contexts;
        this.commitRunner = commitRunner;
        this.rollback = rollback;
        this.sources = sources;
        this.samples = samples;
        this.csvSource = csvSource;
        this.events = events;
        this.caches = caches;
        this.clock = clock;
    }

    /**
     * Stores the file, detects its columns and validates it.
     *
     * <p>The file is stored before it is parsed. A file that turns out to be unreadable is
     * still the file the user sent, and keeping it is what makes "it worked in the browser"
     * answerable.
     */
    @Transactional
    ImportBatchView upload(ImportKind kind, MultipartFile file, UUID dataSourceId) {
        UUID tenantId = TenantContext.requireTenantId();

        if (sources.findFirstByTenantIdAndKind(tenantId, DataSourceKind.SAMPLE).isPresent()) {
            throw ApiException.conflict("remove_sample_first",
                    "This workspace runs on the Hardin sample. Remove it before loading your own files.");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_upload", "Choose a file to import.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("upload_too_large",
                    "That file is larger than 200 MB. Split it by date range and import each part.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw ApiException.badRequest("upload_unreadable", "The uploaded file could not be read.");
        }
        ImportBatchEntity batch = createBatch(kind, "upload", sanitiseFileName(file.getOriginalFilename()), bytes,
                dataSourceId);
        return view(batch);
    }

    /**
     * Stores the bytes as a batch of {@code kind} from {@code source}, detects the columns and
     * validates. The one path every batch - upload or sample - is born through.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    ImportBatchEntity createBatch(ImportKind kind, String source, String fileName, byte[] bytes, UUID dataSourceId) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId().orElse(null);

        String fileKey = files.store(tenantId, fileName, new ByteArrayInputStream(bytes));
        ImportBatchEntity batch = batches.saveAndFlush(new ImportBatchEntity(
                tenantId, kind, source, dataSourceId, fileKey, fileName, bytes.length, SampleFiles.sha256(bytes), userId));

        ValidationContext ctx = contexts.forBatch(tenantId, kind, source);
        ImportReport<?> report = validateDetected(kinds.validator(kind), new String(bytes, StandardCharsets.UTF_8), ctx);
        applyReport(batch, report);

        log.info("Import {} ({}, {}) created: {} rows, {} accepted, {} rejected",
                batch.getId(), kind.key(), source, report.totalRows(), report.acceptedRows(), report.rejectedRows());
        return batch;
    }

    private static <R extends AcceptedRow> ImportReport<R> validateDetected(KindValidator<R> validator, String csv,
            ValidationContext ctx) {
        List<List<String>> rows = com.aatlas.common.csv.CsvReader.parse(csv);
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        return validator.validate(rows, ColumnMapping.detect(validator.kind(), headers), ctx);
    }

    @Transactional(readOnly = true)
    ImportBatchView get(UUID id) {
        return view(load(id));
    }

    @Transactional(readOnly = true)
    List<ImportBatchView> list(ImportKind kind) {
        UUID tenantId = TenantContext.requireTenantId();
        List<ImportBatchEntity> rows = kind == null
                ? batches.findByTenantIdOrderByCreatedAtDesc(tenantId)
                : batches.findByTenantIdAndKindOrderByCreatedAtDesc(tenantId, kind.key());
        return rows.stream().map(this::view).toList();
    }

    ImportFieldsView fields(ImportKind kind) {
        return ImportFieldsView.of(kind);
    }

    /**
     * Reassigns columns and validates again from the stored file.
     *
     * <p>Revalidating rather than patching the previous report: a mapping change can turn
     * every row from accepted to rejected, and there is no shortcut that is also correct.
     */
    @Transactional
    ImportBatchView updateMapping(UUID id, Map<String, Integer> requested) {
        ImportBatchEntity batch = load(id);
        if (batch.getStatus() == ImportBatchStatus.COMMITTED || batch.getStatus() == ImportBatchStatus.COMMITTING
                || batch.getStatus() == ImportBatchStatus.ROLLED_BACK) {
            throw ApiException.conflict("import_already_committed",
                    "This file has already been imported. Upload it again to load it differently.");
        }
        ImportKind kind = batch.kind();
        ValidationContext ctx = contexts.forBatch(batch.getTenantId(), kind, batch.getSource());
        ImportReport<?> report = kinds.validator(kind)
                .validate(files.readAsString(batch.getFileKey()), toMapping(kind, requested), ctx);
        applyReport(batch, report);
        return view(batch);
    }

    /**
     * Starts the load and returns straight away.
     *
     * <p>The status flips to {@code COMMITTING} in this transaction, so a second commit on
     * the same batch is refused by the check below rather than loading everything twice.
     */
    @Transactional
    ImportBatchView commit(UUID id) {
        ImportBatchEntity batch = load(id);

        switch (batch.getStatus()) {
            case NEEDS_MAPPING -> throw ApiException.badRequest("mapping_incomplete",
                    "Choose a column for every required field before importing.");
            case COMMITTING -> throw ApiException.conflict("import_in_progress",
                    "This file is already being imported.");
            case COMMITTED -> throw ApiException.conflict("import_already_committed",
                    "This file has already been imported.");
            case ROLLED_BACK -> throw ApiException.conflict("import_rolled_back",
                    "This file was rolled back. Upload it again to load it.");
            case FAILED, VALIDATED -> { /* a failed import may be retried */ }
        }
        if (batch.getAcceptedRows() == 0) {
            throw ApiException.badRequest("nothing_to_import",
                    "No rows in this file passed validation, so there is nothing to import.");
        }
        if (!batch.isSampleSource() && samples.isSampleHash(batch.getContentHash())) {
            throw ApiException.conflict("sample_file",
                    "This is the Hardin sample, not your data — try it in a sample workspace.");
        }

        batch.markCommitting();
        batches.saveAndFlush(batch);

        // After commit, not inside it: the worker reads this row, and starting it while the
        // transaction is open is a race it would sometimes lose.
        commitRunner.runAfterCommit(TenantContext.current().orElseThrow(), batch.getId());
        return view(batch);
    }

    /**
     * Removes an import: a batch that never loaded is simply deleted; a committed one is
     * rolled back with everything it loaded and everything it created that nothing else uses.
     *
     * @return the rolled-back view, or empty when the batch was deleted outright
     */
    @Transactional
    Optional<ImportBatchView> delete(UUID id) {
        ImportBatchEntity batch = load(id);
        UUID tenantId = batch.getTenantId();
        switch (batch.getStatus()) {
            case COMMITTING -> throw ApiException.conflict("import_in_progress",
                    "This file is being imported. Wait for it to finish before rolling it back.");
            case ROLLED_BACK -> throw ApiException.conflict("already_rolled_back",
                    "This import has already been rolled back.");
            case COMMITTED -> {
                List<String> kept = rollback.rollback(batch);
                batch.markRolledBack(clock.now(), kept);
                // Flushed, not just saved: refreshAfterRollback reads this tenant's batches back
                // through a plain JdbcTemplate query, which bypasses the persistence context and
                // would otherwise still see this batch as COMMITTED until the transaction ends.
                batches.saveAndFlush(batch);
                events.publish(new ImportRolledBack(tenantId, batch.getId(), batch.getKind(), clock.now()));
                if (!batch.isSampleSource()) {
                    csvSource.refreshAfterRollback(tenantId);
                }
                caches.evictAfterCommit(tenantId);
                log.info("Import {} ({}) rolled back; {} suppliers kept", batch.getId(), batch.getKind(), kept.size());
                return Optional.of(view(batch));
            }
            default -> {
                issues.deleteByBatch(tenantId, batch.getId());
                batches.delete(batch);
                files.delete(batch.getFileKey());
                return Optional.empty();
            }
        }
    }

    @Transactional(readOnly = true)
    List<ImportIssueView> issues(UUID id, String severity, int limit, int offset) {
        UUID tenantId = TenantContext.requireTenantId();
        load(id); // 404 before paging into nothing.

        PageRequest page = PageRequest.of(offset / Math.max(1, limit), limit);
        List<ImportIssueEntity> rows = severity == null || severity.isBlank()
                ? issues.findByTenantIdAndBatchIdOrderByLineAsc(tenantId, id, page)
                : issues.findByTenantIdAndBatchIdAndSeverityOrderByLineAsc(
                        tenantId, id, severity.strip().toLowerCase(java.util.Locale.ROOT), page);
        return rows.stream().map(ImportIssueView::of).toList();
    }

    // ---- internals ---------------------------------------------------------

    private ImportBatchEntity load(UUID id) {
        return batches.findByTenantIdAndId(TenantContext.requireTenantId(), id)
                .orElseThrow(() -> ApiException.notFound("Import", id));
    }

    /** Writes the report onto the batch and replaces its issues. */
    private void applyReport(ImportBatchEntity batch, ImportReport<?> report) {
        UUID tenantId = batch.getTenantId();

        batch.applyReport(
                report.headers(),
                wireMapping(report.mapping()),
                report.missingRequired().stream().map(ImportFieldSpec::key).toList(),
                report.sample().stream().map(AcceptedRow::preview).toList(),
                report.totalRows(),
                report.acceptedRows(),
                report.rejectedRows(),
                report.distinctItems(),
                report.distinctCustomers(),
                report.distinctBranches(),
                report.distinctSuppliers(),
                report.earliest(),
                report.latest(),
                report.monthsCovered());
        batches.save(batch);

        issues.deleteByBatch(tenantId, batch.getId());
        List<ImportIssueEntity> rows = report.issues().stream()
                .map(issue -> new ImportIssueEntity(
                        tenantId,
                        batch.getId(),
                        issue.line(),
                        issue.field() == null ? "row" : issue.field().key(),
                        issue.message(),
                        issue.severity().wireValue()))
                .toList();
        issues.saveAll(rows);
    }

    ImportBatchView view(ImportBatchEntity batch) {
        UUID tenantId = batch.getTenantId();
        List<ImportIssueView> inline = issues
                .findByTenantIdAndBatchIdOrderByLineAsc(tenantId, batch.getId(), PageRequest.of(0, INLINE_ISSUES))
                .stream()
                .map(ImportIssueView::of)
                .toList();
        UUID duplicateOf = batch.getContentHash() == null ? null
                : batches.findFirstByTenantIdAndKindAndContentHashAndStatusAndIdNotOrderByCommittedAtDesc(
                        tenantId, batch.getKind(), batch.getContentHash(), ImportBatchStatus.COMMITTED, batch.getId())
                        .map(ImportBatchEntity::getId)
                        .orElse(null);
        boolean isSample = samples.isSampleHash(batch.getContentHash());
        return ImportBatchView.of(batch, inline, issues.countByTenantIdAndBatchId(tenantId, batch.getId()),
                duplicateOf, isSample);
    }

    /** Wire keys in, fields out, with an unknown field or a negative column refused. */
    static ColumnMapping toMapping(ImportKind kind, Map<String, Integer> requested) {
        Map<ImportFieldSpec, Integer> columns = new LinkedHashMap<>();
        requested.forEach((key, index) -> {
            if (index == null) {
                return; // An explicit null clears a field, same as leaving it out.
            }
            if (index < 0) {
                throw ApiException.badRequest("invalid_mapping",
                        "Column index for \"" + key + "\" must be zero or greater.");
            }
            ImportFieldSpec field;
            try {
                field = kind.field(key);
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_mapping", ex.getMessage());
            }
            columns.put(field, index);
        });
        return new ColumnMapping(kind, columns);
    }

    private static Map<String, Integer> wireMapping(ColumnMapping mapping) {
        Map<String, Integer> wire = new LinkedHashMap<>();
        mapping.columns().forEach((field, index) -> wire.put(field.key(), index));
        return wire;
    }

    /**
     * Keeps the name for display and throws away any path in it.
     *
     * <p>Browsers send a bare name, but the header is caller-controlled and this string is
     * shown back to users and written to the database. It never reaches the filesystem -
     * {@link ImportFileStore} generates its own key - so this is about display and storage,
     * not traversal.
     */
    static String sanitiseFileName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.csv";
        }
        String name = original.strip().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) {
            return "upload.csv";
        }
        return name.length() <= 200 ? name : name.substring(0, 200);
    }
}

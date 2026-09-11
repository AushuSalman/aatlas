package com.aatlas.ingest.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.ingest.internal.ImportBatchView.ImportIssueView;
import com.aatlas.ingest.internal.csv.ColumnMapping;
import com.aatlas.ingest.internal.csv.ImportField;
import com.aatlas.ingest.internal.csv.ImportReport;
import com.aatlas.ingest.internal.csv.ImportValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading, revalidating and committing a CSV.
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
    private final ImportValidator validator;
    private final ImportCommitRunner commitRunner;

    ImportService(
            ImportBatchRepository batches,
            ImportIssueRepository issues,
            ImportFileStore files,
            ImportValidator validator,
            ImportCommitRunner commitRunner) {
        this.batches = batches;
        this.issues = issues;
        this.files = files;
        this.validator = validator;
        this.commitRunner = commitRunner;
    }

    /**
     * Stores the file, detects its columns and validates it.
     *
     * <p>The file is stored before it is parsed. A file that turns out to be unreadable is
     * still the file the user sent, and keeping it is what makes "it worked in the browser"
     * answerable.
     */
    @Transactional
    ImportBatchView upload(MultipartFile file, UUID dataSourceId) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId().orElse(null);

        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("empty_upload", "Choose a file to import.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("upload_too_large",
                    "That file is larger than 200 MB. Split it by date range and import each part.");
        }

        String fileName = sanitiseFileName(file.getOriginalFilename());
        String fileKey;
        try (InputStream content = file.getInputStream()) {
            fileKey = files.store(tenantId, fileName, content);
        } catch (IOException ex) {
            throw ApiException.badRequest("upload_unreadable", "The uploaded file could not be read.");
        }

        ImportBatchEntity batch = batches.saveAndFlush(
                new ImportBatchEntity(tenantId, dataSourceId, fileKey, fileName, file.getSize(), userId));

        ImportReport report = validator.validateWithDetectedMapping(files.readAsString(fileKey));
        applyReport(batch, report);

        log.info("Import {} uploaded: {} rows, {} accepted, {} rejected",
                batch.getId(), report.totalRows(), report.acceptedRows(), report.rejectedRows());
        return view(batch);
    }

    @Transactional(readOnly = true)
    ImportBatchView get(UUID id) {
        return view(load(id));
    }

    @Transactional(readOnly = true)
    List<ImportBatchView> list() {
        return batches.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()).stream()
                .map(this::view)
                .toList();
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
        if (batch.getStatus() == ImportBatchStatus.COMMITTED || batch.getStatus() == ImportBatchStatus.COMMITTING) {
            throw ApiException.conflict("import_already_committed",
                    "This file has already been imported. Upload it again to load it differently.");
        }

        ImportReport report = validator.validate(files.readAsString(batch.getFileKey()), toMapping(requested));
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
            case FAILED, VALIDATED -> { /* a failed import may be retried */ }
        }
        if (batch.getAcceptedRows() == 0) {
            throw ApiException.badRequest("nothing_to_import",
                    "No rows in this file passed validation, so there is nothing to import.");
        }

        batch.markCommitting();
        batches.saveAndFlush(batch);

        // After commit, not inside it: the worker reads this row, and starting it while the
        // transaction is open is a race it would sometimes lose.
        commitRunner.runAfterCommit(TenantContext.current().orElseThrow(), batch.getId());
        return view(batch);
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
    private void applyReport(ImportBatchEntity batch, ImportReport report) {
        UUID tenantId = batch.getTenantId();

        batch.applyReport(
                report.headers(),
                wireMapping(report.mapping()),
                report.missingRequired().stream().map(ImportField::key).toList(),
                report.sample().stream().map(ImportService::sampleRow).toList(),
                report.totalRows(),
                report.acceptedRows(),
                report.rejectedRows(),
                report.distinctItems(),
                report.distinctCustomers(),
                report.distinctBranches(),
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

    private ImportBatchView view(ImportBatchEntity batch) {
        UUID tenantId = batch.getTenantId();
        List<ImportIssueView> inline = issues
                .findByTenantIdAndBatchIdOrderByLineAsc(tenantId, batch.getId(), PageRequest.of(0, INLINE_ISSUES))
                .stream()
                .map(ImportIssueView::of)
                .toList();
        return ImportBatchView.of(batch, inline, issues.countByTenantIdAndBatchId(tenantId, batch.getId()));
    }

    /** Wire keys in, enum out, with an unknown field or a negative column refused. */
    private static ColumnMapping toMapping(Map<String, Integer> requested) {
        Map<ImportField, Integer> columns = new EnumMap<>(ImportField.class);
        requested.forEach((key, index) -> {
            if (index == null) {
                return; // An explicit null clears a field, same as leaving it out.
            }
            if (index < 0) {
                throw ApiException.badRequest("invalid_mapping",
                        "Column index for \"" + key + "\" must be zero or greater.");
            }
            ImportField field;
            try {
                field = ImportField.from(key);
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_mapping", ex.getMessage());
            }
            columns.put(field, index);
        });
        return new ColumnMapping(columns);
    }

    private static Map<String, Integer> wireMapping(ColumnMapping mapping) {
        Map<String, Integer> wire = new LinkedHashMap<>();
        mapping.columns().forEach((field, index) -> wire.put(field.key(), index));
        return wire;
    }

    /** The preview row, keyed as the frontend's {@code ParsedRow}. */
    private static Map<String, Object> sampleRow(ImportReport.ParsedRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("item", row.item());
        out.put("description", row.description());
        out.put("date", row.date() == null ? null : row.date().toString());
        out.put("qty", row.qty());
        out.put("price", row.price());
        out.put("cost", row.cost());
        out.put("customer", row.customer());
        out.put("branch", row.branch());
        return out;
    }

    /**
     * Keeps the name for display and throws away any path in it.
     *
     * <p>Browsers send a bare name, but the header is caller-controlled and this string is
     * shown back to users and written to the database. It never reaches the filesystem -
     * {@link ImportFileStore} generates its own key - so this is about display and storage,
     * not traversal.
     */
    private static String sanitiseFileName(String original) {
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

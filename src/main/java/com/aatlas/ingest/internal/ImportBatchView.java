package com.aatlas.ingest.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An import as the connect screen sees it.
 *
 * <p>Shaped to match {@code ImportReport} in the frontend's {@code ingest.ts} so the screen
 * renders a server report with the component it already uses for the one it computes in the
 * browser, plus the fields only a server can supply: an id, a status, what the commit
 * actually did, and whether this file has been seen before.
 *
 * @param summary kind-specific commit counters ({@code customersCreated}, {@code pricesWritten},
 *     {@code observationsWritten}, ...), verbatim from the load
 * @param duplicateOf an earlier committed batch of the same kind with the same bytes, if any
 * @param isSample whether the bytes are one of the Hardin sample files
 */
@Schema(name = "ImportBatch", description = "An uploaded file, its validation report, and its commit status.")
record ImportBatchView(
        UUID id,
        String kind,
        String source,
        String fileName,
        long fileSize,
        String contentHash,
        ImportBatchStatus status,
        UUID dataSourceId,

        List<String> headers,
        Map<String, Integer> mapping,
        List<String> missingRequired,

        int totalRows,
        int acceptedRows,
        int rejectedRows,
        int distinctItems,
        int distinctCustomers,
        int distinctBranches,
        int distinctSuppliers,
        LocalDate earliest,
        LocalDate latest,
        int monthsCovered,

        List<Map<String, Object>> sample,
        List<ImportIssueView> issues,
        long issueCount,

        /** Null until the commit finishes. */
        Integer loadedRows,
        Integer productsCreated,
        Integer branchesCreated,
        List<String> branchesNeedingRegion,
        Map<String, Object> summary,
        int dateShiftDays,
        UUID duplicateOf,
        boolean isSample,
        String failureReason,
        Instant committedAt,
        Instant rolledBackAt,
        Instant createdAt) {

    /**
     * Whether the client may call commit. Saves it reimplementing the rule.
     *
     * <p>{@code @JsonProperty}: a record's derived (non-component) accessor is invisible to
     * Jackson by default - only the canonical components serialise on their own - so without
     * this annotation the field is silently absent from the wire rather than sent as false.
     */
    @JsonProperty
    public boolean committable() {
        return status == ImportBatchStatus.VALIDATED && acceptedRows > 0;
    }

    static ImportBatchView of(ImportBatchEntity batch, List<ImportIssueView> issues, long issueCount,
            UUID duplicateOf, boolean isSample) {
        return new ImportBatchView(
                batch.getId(),
                batch.getKind(),
                batch.getSource(),
                batch.getFileName(),
                batch.getFileSize(),
                batch.getContentHash(),
                batch.getStatus(),
                batch.getDataSourceId(),
                batch.getHeaders(),
                batch.getMapping(),
                batch.getMissingRequired(),
                batch.getTotalRows(),
                batch.getAcceptedRows(),
                batch.getRejectedRows(),
                batch.getDistinctItems(),
                batch.getDistinctCustomers(),
                batch.getDistinctBranches(),
                batch.getDistinctSuppliers(),
                batch.getEarliest(),
                batch.getLatest(),
                batch.getMonthsCovered(),
                batch.getSample(),
                issues,
                issueCount,
                batch.getLoadedRows(),
                batch.getProductsCreated(),
                batch.getBranchesCreated(),
                batch.getBranchesNeedingRegion(),
                batch.getCommitSummary(),
                batch.getDateShiftDays(),
                duplicateOf,
                isSample,
                batch.getFailureReason(),
                batch.getCommittedAt(),
                batch.getRolledBackAt(),
                batch.getCreatedAt());
    }

    /**
     * One row problem.
     *
     * @param line 1-based, counting the header - the number a spreadsheet shows
     * @param severity {@code error} rejected the row; {@code warning} kept it
     */
    @Schema(name = "ImportIssue")
    record ImportIssueView(int line, String field, String message, String severity) {

        static ImportIssueView of(ImportIssueEntity issue) {
            return new ImportIssueView(
                    issue.getLine(), issue.getField(), issue.getMessage(), issue.getSeverity());
        }
    }
}

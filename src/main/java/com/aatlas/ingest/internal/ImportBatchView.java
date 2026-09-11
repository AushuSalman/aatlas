package com.aatlas.ingest.internal;

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
 * browser, plus the fields only a server can supply: an id, a status, and what the commit
 * actually did.
 */
@Schema(name = "ImportBatch", description = "An uploaded file, its validation report, and its commit status.")
record ImportBatchView(
        UUID id,
        String fileName,
        long fileSize,
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
        List<String> unresolvedCustomers,
        String failureReason,
        Instant committedAt,
        Instant createdAt) {

    /** Whether the client may call commit. Saves it reimplementing the rule. */
    public boolean committable() {
        return status == ImportBatchStatus.VALIDATED && acceptedRows > 0;
    }

    static ImportBatchView of(ImportBatchEntity batch, List<ImportIssueView> issues, long issueCount) {
        return new ImportBatchView(
                batch.getId(),
                batch.getFileName(),
                batch.getFileSize(),
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
                batch.getUnresolvedCustomers(),
                batch.getFailureReason(),
                batch.getCommittedAt(),
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

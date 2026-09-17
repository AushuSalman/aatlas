package com.aatlas.ingest.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.ingest.internal.csv.ImportKind;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * One uploaded file: what it is, where it is stored, what validation found, and how the
 * commit went.
 *
 * <p>The report fields are written twice in a batch's life - once when the file is uploaded
 * and again whenever the user changes a column mapping - so validation is cheap and
 * repeatable by design. The commit fields are written once, at the end; a rollback writes
 * the last two.
 */
@Entity
@Table(name = "import_batches")
public class ImportBatchEntity extends TenantScopedEntity {

    @Column(name = "data_source_id")
    private UUID dataSourceId;

    /** {@code sales | purchases | products | competitor_prices}. */
    @Column(name = "kind", nullable = false, updatable = false)
    private String kind = "sales";

    /** {@code upload} for the tenant's own file, {@code sample} for the Hardin sample. */
    @Column(name = "source", nullable = false, updatable = false)
    private String source = "upload";

    /** Opaque here; {@link ImportFileStore} owns what it means. */
    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    /** SHA-256 of the stored bytes: finds a re-upload, and recognises the sample. */
    @Column(name = "content_hash", updatable = false)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ImportBatchStatus status = ImportBatchStatus.VALIDATED;

    @Type(JsonType.class)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    private List<String> headers = List.of();

    /** Field key to zero-based column index, e.g. {@code {"item": 0, "qty": 3}}. */
    @Type(JsonType.class)
    @Column(name = "mapping", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> mapping = Map.of();

    @Type(JsonType.class)
    @Column(name = "missing_required", nullable = false, columnDefinition = "jsonb")
    private List<String> missingRequired = List.of();

    /** The preview rows, already shaped for the screen. */
    @Type(JsonType.class)
    @Column(name = "sample", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> sample = List.of();

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "accepted_rows", nullable = false)
    private int acceptedRows;

    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows;

    @Column(name = "distinct_items", nullable = false)
    private int distinctItems;

    @Column(name = "distinct_customers", nullable = false)
    private int distinctCustomers;

    @Column(name = "distinct_branches", nullable = false)
    private int distinctBranches;

    @Column(name = "distinct_suppliers", nullable = false)
    private int distinctSuppliers;

    @Column(name = "months_covered", nullable = false)
    private int monthsCovered;

    @Column(name = "earliest")
    private LocalDate earliest;

    @Column(name = "latest")
    private LocalDate latest;

    @Column(name = "loaded_rows")
    private Integer loadedRows;

    @Column(name = "products_created")
    private Integer productsCreated;

    @Column(name = "branches_created")
    private Integer branchesCreated;

    /** Branch codes this import created with no region yet. */
    @Type(JsonType.class)
    @Column(name = "branches_needing_region", nullable = false, columnDefinition = "jsonb")
    private List<String> branchesNeedingRegion = List.of();

    /** Kind-specific commit counters, rendered verbatim. */
    @Type(JsonType.class)
    @Column(name = "commit_summary", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> commitSummary = Map.of();

    /** Sample files only: how many days every date was moved forward at load. */
    @Column(name = "date_shift_days", nullable = false)
    private int dateShiftDays;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "uploaded_by", updatable = false)
    private UUID uploadedBy;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    protected ImportBatchEntity() {
        // JPA
    }

    ImportBatchEntity(UUID tenantId, ImportKind kind, String source, UUID dataSourceId, String fileKey,
            String fileName, long fileSize, String contentHash, UUID uploadedBy) {
        setTenantId(tenantId);
        this.kind = kind.key();
        this.source = source;
        this.dataSourceId = dataSourceId;
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentHash = contentHash;
        this.uploadedBy = uploadedBy;
    }

    /** Replaces the report. Called on upload and on every mapping change. */
    void applyReport(
            List<String> headers,
            Map<String, Integer> mapping,
            List<String> missingRequired,
            List<Map<String, Object>> sample,
            int totalRows,
            int acceptedRows,
            int rejectedRows,
            int distinctItems,
            int distinctCustomers,
            int distinctBranches,
            int distinctSuppliers,
            LocalDate earliest,
            LocalDate latest,
            int monthsCovered) {
        this.headers = headers;
        this.mapping = mapping;
        this.missingRequired = missingRequired;
        this.sample = sample;
        this.totalRows = totalRows;
        this.acceptedRows = acceptedRows;
        this.rejectedRows = rejectedRows;
        this.distinctItems = distinctItems;
        this.distinctCustomers = distinctCustomers;
        this.distinctBranches = distinctBranches;
        this.distinctSuppliers = distinctSuppliers;
        this.earliest = earliest;
        this.latest = latest;
        this.monthsCovered = monthsCovered;
        this.status = missingRequired.isEmpty() ? ImportBatchStatus.VALIDATED : ImportBatchStatus.NEEDS_MAPPING;
        this.failureReason = null;
    }

    /** Whether the file can be committed as it stands. */
    boolean committable() {
        return status == ImportBatchStatus.VALIDATED && acceptedRows > 0;
    }

    void markCommitting() {
        this.status = ImportBatchStatus.COMMITTING;
        this.failureReason = null;
    }

    /**
     * Records what the load did. For a sample batch the report's date range is moved forward
     * with the rows, so the view describes what is actually in the tables.
     */
    void markCommitted(Instant at, LoadResult result, int dateShiftDays) {
        this.status = ImportBatchStatus.COMMITTED;
        this.committedAt = at;
        this.loadedRows = result.loadedRows();
        this.productsCreated = result.productsCreated();
        this.branchesCreated = result.branchesCreated();
        this.branchesNeedingRegion = result.branchesNeedingRegion();
        this.distinctSuppliers = Math.max(this.distinctSuppliers, result.distinctSuppliers());
        this.commitSummary = new LinkedHashMap<>(result.summary());
        this.dateShiftDays = dateShiftDays;
        if (dateShiftDays > 0) {
            this.earliest = earliest == null ? null : earliest.plusDays(dateShiftDays);
            this.latest = latest == null ? null : latest.plusDays(dateShiftDays);
        }
    }

    void markFailed(String reason) {
        this.status = ImportBatchStatus.FAILED;
        // Truncated: this is shown to a user, and a driver's stack trace is neither useful
        // to them nor something we want on a screen.
        this.failureReason = reason == null ? "The import failed." : reason.substring(0, Math.min(500, reason.length()));
    }

    void markRolledBack(Instant at, List<String> keptSuppliers) {
        this.status = ImportBatchStatus.ROLLED_BACK;
        this.rolledBackAt = at;
        Map<String, Object> summary = new LinkedHashMap<>(commitSummary == null ? Map.of() : commitSummary);
        summary.put("keptSuppliers", keptSuppliers == null ? List.of() : List.copyOf(keptSuppliers));
        this.commitSummary = summary;
    }

    void setDataSourceId(UUID dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public UUID getDataSourceId() {
        return dataSourceId;
    }

    public ImportKind kind() {
        return ImportKind.from(kind);
    }

    public String getKind() {
        return kind;
    }

    public String getSource() {
        return source;
    }

    public boolean isSampleSource() {
        return "sample".equals(source);
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getContentHash() {
        return contentHash;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public List<String> getHeaders() {
        return headers;
    }

    public Map<String, Integer> getMapping() {
        return mapping;
    }

    public List<String> getMissingRequired() {
        return missingRequired;
    }

    public List<Map<String, Object>> getSample() {
        return sample;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getAcceptedRows() {
        return acceptedRows;
    }

    public int getRejectedRows() {
        return rejectedRows;
    }

    public int getDistinctItems() {
        return distinctItems;
    }

    public int getDistinctCustomers() {
        return distinctCustomers;
    }

    public int getDistinctBranches() {
        return distinctBranches;
    }

    public int getDistinctSuppliers() {
        return distinctSuppliers;
    }

    public int getMonthsCovered() {
        return monthsCovered;
    }

    public LocalDate getEarliest() {
        return earliest;
    }

    public LocalDate getLatest() {
        return latest;
    }

    public Integer getLoadedRows() {
        return loadedRows;
    }

    public Integer getProductsCreated() {
        return productsCreated;
    }

    public Integer getBranchesCreated() {
        return branchesCreated;
    }

    public List<String> getBranchesNeedingRegion() {
        return branchesNeedingRegion;
    }

    public Map<String, Object> getCommitSummary() {
        return commitSummary == null ? Map.of() : commitSummary;
    }

    public int getDateShiftDays() {
        return dateShiftDays;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }
}

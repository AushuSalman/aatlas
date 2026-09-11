package com.aatlas.ingest.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One problem with one row of an uploaded file.
 *
 * <p>Immutable once written: validation replaces a batch's issues wholesale rather than
 * editing them, because a mapping change invalidates every conclusion drawn under the old
 * one.
 *
 * <p>Extends {@code TenantScopedEntity} rather than hanging off the batch alone. The
 * duplicate tenant column is what lets the issues index lead with the tenant and what makes
 * the row-level security policy on this table the same as every other.
 */
@Entity
@Table(name = "import_issues")
public class ImportIssueEntity extends TenantScopedEntity {

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    /** 1-based and counting the header, so it matches what a spreadsheet shows. */
    @Column(name = "line", nullable = false, updatable = false)
    private int line;

    /** The field key, or {@code row} for a problem with the line as a whole. */
    @Column(name = "field", nullable = false, updatable = false)
    private String field;

    @Column(name = "message", nullable = false, updatable = false)
    private String message;

    /** {@code error} rejects the row; {@code warning} keeps it and says so. */
    @Column(name = "severity", nullable = false, updatable = false)
    private String severity;

    protected ImportIssueEntity() {
        // JPA
    }

    ImportIssueEntity(UUID tenantId, UUID batchId, int line, String field, String message, String severity) {
        setTenantId(tenantId);
        this.batchId = batchId;
        this.line = line;
        this.field = field;
        this.message = message;
        this.severity = severity;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getLine() {
        return line;
    }

    public String getField() {
        return field;
    }

    public String getMessage() {
        return message;
    }

    public String getSeverity() {
        return severity;
    }
}

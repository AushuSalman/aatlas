package com.aatlas.ingest.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import com.aatlas.ingest.DataSourceView;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A connected source of history. See {@code V7}.
 *
 * <p>{@code config} holds whatever the connector needs (host, credentials reference,
 * share path) and never leaves the server: {@link #toView()} omits it. The blueprint
 * wants it encrypted at rest; that lands with the first real connector.
 */
@Entity
@Table(name = "data_sources")
public class DataSourceEntity extends TenantScopedEntity {

    @Convert(converter = DataSourceKind.JpaConverter.class)
    @Column(name = "kind", nullable = false, updatable = false)
    private DataSourceKind kind;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "detail", nullable = false)
    private String detail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config")
    private Map<String, Object> config;

    @Column(name = "schedule")
    private String schedule;

    @Convert(converter = DataSourceStatus.JpaConverter.class)
    @Column(name = "status", nullable = false)
    private DataSourceStatus status;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    @Column(name = "connected_by", updatable = false)
    private UUID connectedBy;

    protected DataSourceEntity() {
        // JPA
    }

    DataSourceEntity(
            UUID tenantId,
            DataSourceKind kind,
            String label,
            String detail,
            Map<String, Object> config,
            String schedule,
            DataSourceStatus status,
            Instant connectedAt,
            Instant lastSyncAt,
            UUID connectedBy) {
        setTenantId(tenantId);
        this.kind = kind;
        this.label = label;
        this.detail = detail == null ? "" : detail;
        this.config = config;
        this.schedule = schedule;
        this.status = status;
        this.connectedAt = connectedAt;
        this.lastSyncAt = lastSyncAt;
        this.connectedBy = connectedBy;
    }

    DataSourceView toView() {
        return new DataSourceView(getId(), kind.wireValue(), label, detail, status.wireValue(), connectedAt, lastSyncAt);
    }

    /** Stamps a sync: the sample loader's claim, the reload endpoint. */
    void touchSync(Instant at) {
        this.lastSyncAt = at;
    }

    public DataSourceKind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public String getDetail() {
        return detail;
    }

    public String getSchedule() {
        return schedule;
    }

    public DataSourceStatus getStatus() {
        return status;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public UUID getConnectedBy() {
        return connectedBy;
    }
}

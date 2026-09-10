package com.aatlas.integrations.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A tenant's connection to one catalogued business system. Row absent = not connected
 * ({@code available}); row present with {@code status='connected'} = connected.
 *
 * <p>{@code config} is stored plain, not encrypted at rest - see {@code docs/decisions.md}:
 * this is demo/sample connection config, not a live secret, and the brief explicitly
 * allows a plain column for that case.
 */
@Entity
@Table(name = "integration_connection")
public class IntegrationConnectionEntity extends TenantScopedEntity {

    @Column(name = "integration_key", nullable = false, updatable = false)
    private String integrationKey;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "status", nullable = false)
    private String status;

    @Type(JsonType.class)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "connected_by")
    private UUID connectedBy;

    protected IntegrationConnectionEntity() {
        // JPA
    }

    IntegrationConnectionEntity(UUID tenantId, String integrationKey, String category, Map<String, Object> config,
            Instant connectedAt, UUID connectedBy) {
        setTenantId(tenantId);
        this.integrationKey = integrationKey;
        this.category = category;
        this.status = "connected";
        this.config = config == null ? Map.of() : config;
        this.connectedAt = connectedAt;
        this.connectedBy = connectedBy;
    }

    /** Reconnecting updates the config and who/when, rather than being a no-op. */
    void reconnect(Map<String, Object> config, Instant connectedAt, UUID connectedBy) {
        this.status = "connected";
        this.config = config == null ? Map.of() : config;
        this.connectedAt = connectedAt;
        this.connectedBy = connectedBy;
    }

    public String getIntegrationKey() {
        return integrationKey;
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public UUID getConnectedBy() {
        return connectedBy;
    }
}

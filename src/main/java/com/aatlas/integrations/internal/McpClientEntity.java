package com.aatlas.integrations.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A tenant's connection to one MCP client kind (claude / gemini / other). */
@Entity
@Table(name = "mcp_client")
public class McpClientEntity extends TenantScopedEntity {

    @Column(name = "client_key", nullable = false, updatable = false)
    private String clientKey;

    @Column(name = "connected", nullable = false)
    private boolean connected;

    @Column(name = "connected_at")
    private Instant connectedAt;

    protected McpClientEntity() {
        // JPA
    }

    McpClientEntity(UUID tenantId, String clientKey, boolean connected, Instant connectedAt) {
        setTenantId(tenantId);
        this.clientKey = clientKey;
        this.connected = connected;
        this.connectedAt = connectedAt;
    }

    public String getClientKey() {
        return clientKey;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(Instant connectedAt) {
        this.connectedAt = connectedAt;
    }
}

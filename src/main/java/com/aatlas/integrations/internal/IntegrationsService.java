package com.aatlas.integrations.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.integrations.internal.IntegrationsDtos.GroupInfoView;
import com.aatlas.integrations.internal.IntegrationsDtos.IntegrationView;
import com.aatlas.integrations.internal.IntegrationsDtos.McpClientView;
import com.aatlas.integrations.internal.IntegrationsDtos.McpPermissionView;
import com.aatlas.integrations.internal.IntegrationsDtos.McpPermissionsView;
import com.aatlas.integrations.internal.IntegrationsDtos.McpView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business systems, MCP clients and MCP permissions: a static catalogue (see
 * {@link IntegrationCatalog}) merged with this tenant's connection state.
 */
@Service
@Transactional
public class IntegrationsService {

    private final IntegrationConnectionRepository connections;
    private final McpClientRepository mcpClients;
    private final McpPermissionRepository mcpPermissions;
    private final AatlasClock clock;

    IntegrationsService(IntegrationConnectionRepository connections, McpClientRepository mcpClients,
            McpPermissionRepository mcpPermissions, AatlasClock clock) {
        this.connections = connections;
        this.mcpClients = mcpClients;
        this.mcpPermissions = mcpPermissions;
        this.clock = clock;
    }

    // ---- integrations ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<IntegrationView> integrations() {
        UUID tenantId = TenantContext.requireTenantId();
        java.util.Set<String> connected = connections.findByTenantId(tenantId).stream()
                .map(IntegrationConnectionEntity::getIntegrationKey)
                .collect(java.util.stream.Collectors.toSet());
        return IntegrationCatalog.INTEGRATIONS.stream()
                .map(i -> new IntegrationView(i.key(), i.name(), i.category(), i.note(),
                        connected.contains(i.key()) ? "connected" : "available"))
                .toList();
    }

    /**
     * Idempotent, but not a no-op on a second call: reconnecting (e.g. to update
     * credentials) replaces the stored {@code config} and {@code connectedAt}/
     * {@code connectedBy} rather than silently keeping the first connection's.
     */
    public IntegrationView connect(String key, Map<String, Object> config) {
        IntegrationCatalog.IntegrationDef def = requireIntegration(key);
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId().orElse(null);
        IntegrationConnectionEntity existing = connections.findByTenantIdAndIntegrationKey(tenantId, key)
                .orElse(null);
        if (existing == null) {
            connections.save(new IntegrationConnectionEntity(tenantId, key, def.category(), config,
                    clock.now(), userId));
        } else {
            existing.reconnect(config, clock.now(), userId);
        }
        return new IntegrationView(def.key(), def.name(), def.category(), def.note(), "connected");
    }

    public void disconnect(String key) {
        requireIntegration(key);
        connections.deleteByTenantIdAndIntegrationKey(TenantContext.requireTenantId(), key);
    }

    private static IntegrationCatalog.IntegrationDef requireIntegration(String key) {
        IntegrationCatalog.IntegrationDef def = IntegrationCatalog.integration(key);
        if (def == null) {
            throw ApiException.notFound("Integration", key);
        }
        return def;
    }

    // ---- mcp ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public McpView mcp() {
        UUID tenantId = TenantContext.requireTenantId();
        Map<String, Boolean> connected = new LinkedHashMap<>();
        mcpClients.findByTenantId(tenantId).forEach(c -> connected.put(c.getClientKey(), c.isConnected()));
        List<McpClientView> clients = IntegrationCatalog.MCP_CLIENTS.stream()
                .map(c -> new McpClientView(c.key(), c.name(), c.note(), connected.getOrDefault(c.key(), false)))
                .toList();
        return new McpView(IntegrationCatalog.MCP_ENDPOINT, clients);
    }

    public McpClientView connectMcpClient(String key) {
        IntegrationCatalog.McpClientDef def = requireMcpClient(key);
        UUID tenantId = TenantContext.requireTenantId();
        McpClientEntity entity = mcpClients.findByTenantIdAndClientKey(tenantId, key).orElse(null);
        if (entity == null) {
            mcpClients.save(new McpClientEntity(tenantId, key, true, clock.now()));
        } else if (!entity.isConnected()) {
            entity.setConnected(true);
            entity.setConnectedAt(clock.now());
        }
        return new McpClientView(def.key(), def.name(), def.note(), true);
    }

    public void disconnectMcpClient(String key) {
        requireMcpClient(key);
        UUID tenantId = TenantContext.requireTenantId();
        mcpClients.findByTenantIdAndClientKey(tenantId, key).ifPresent(entity -> {
            entity.setConnected(false);
            entity.setConnectedAt(null);
        });
    }

    private static IntegrationCatalog.McpClientDef requireMcpClient(String key) {
        IntegrationCatalog.McpClientDef def = IntegrationCatalog.mcpClient(key);
        if (def == null) {
            throw ApiException.notFound("MCP client", key);
        }
        return def;
    }

    // ---- mcp permissions -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public McpPermissionsView permissions() {
        UUID tenantId = TenantContext.requireTenantId();
        Map<String, McpPermissionEntity> overrides = new LinkedHashMap<>();
        mcpPermissions.findByTenantId(tenantId).forEach(p -> overrides.put(p.getPermissionKey(), p));

        List<McpPermissionView> views = IntegrationCatalog.MCP_PERMISSIONS.stream()
                .map(p -> {
                    McpPermissionEntity override = overrides.get(p.key());
                    boolean enabled = override != null ? override.isEnabled() : p.defaultOn();
                    boolean requiresApproval = override != null ? override.isRequiresApproval() : p.requiresApproval();
                    return new McpPermissionView(p.key(), p.group(), p.label(), requiresApproval, p.defaultOn(),
                            enabled);
                })
                .toList();

        Map<String, GroupInfoView> groups = new LinkedHashMap<>();
        IntegrationCatalog.GROUP_LABEL.forEach((k, v) -> groups.put(k, new GroupInfoView(v.title(), v.blurb())));
        return new McpPermissionsView(groups, views);
    }

    public McpPermissionsView updatePermissions(Map<String, Boolean> changes) {
        UUID tenantId = TenantContext.requireTenantId();
        if (changes != null) {
            for (Map.Entry<String, Boolean> e : changes.entrySet()) {
                IntegrationCatalog.McpPermissionDef def = IntegrationCatalog.permission(e.getKey());
                if (def == null) {
                    throw ApiException.badRequest("unknown_permission", "No such permission: " + e.getKey());
                }
                McpPermissionEntity entity = mcpPermissions.findByTenantIdAndPermissionKey(tenantId, e.getKey())
                        .orElse(null);
                if (entity == null) {
                    mcpPermissions.save(new McpPermissionEntity(tenantId, e.getKey(), e.getValue(),
                            def.requiresApproval()));
                } else {
                    entity.setEnabled(e.getValue());
                }
            }
        }
        return permissions();
    }
}

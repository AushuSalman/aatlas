package com.aatlas.integrations.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

final class IntegrationsDtos {

    private IntegrationsDtos() {
    }

    @Schema(name = "Integration", description = "A business system, catalogue entry plus this tenant's connection state.")
    record IntegrationView(
            String key,
            String name,
            @Schema(allowableValues = {"ERP", "CRM", "Accounting", "Inventory", "Order management",
                    "Supplier systems", "API", "Webhooks"}) String category,
            String note,
            @Schema(allowableValues = {"connected", "available"}) String status) {
    }

    @Schema(name = "McpClient")
    record McpClientView(
            @Schema(allowableValues = {"claude", "gemini", "other"}) String key,
            String name,
            String note,
            boolean connected) {
    }

    @Schema(name = "Mcp")
    record McpView(String endpoint, List<McpClientView> clients) {
    }

    @Schema(name = "GroupLabel")
    record GroupInfoView(String title, String blurb) {
    }

    @Schema(name = "McpPermission")
    record McpPermissionView(
            String key,
            @Schema(allowableValues = {"read", "actions", "restricted"}) String group,
            String label,
            @Schema(description = "Restricted actions always need a person to approve each use.")
                    boolean requiresApproval,
            boolean defaultOn,
            boolean enabled) {
    }

    @Schema(name = "McpPermissions")
    record McpPermissionsView(Map<String, GroupInfoView> groups, List<McpPermissionView> permissions) {
    }

    @Schema(name = "ConnectIntegrationRequest")
    record ConnectIntegrationRequest(Map<String, Object> config) {
    }

    @Schema(name = "UpdateMcpPermissionsRequest")
    record UpdatePermissionsRequest(Map<String, Boolean> permissions) {
    }
}

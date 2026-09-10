package com.aatlas.integrations.internal;

import java.util.List;
import java.util.Map;

/**
 * The static reference data: field-for-field port of
 * {@code src/lib/intel/integrations.ts}'s {@code INTEGRATIONS}, {@code MCP_CLIENTS},
 * {@code MCP_PERMISSIONS} and {@code GROUP_LABEL}. Not tenant-scoped - the same for every
 * tenant, compiled into the app rather than a table, exactly like the frontend's constant
 * arrays. Per-tenant connection state (which of these a tenant has turned on) lives in
 * {@code integration_connection}/{@code mcp_client}/{@code mcp_permission} (migration V13).
 */
final class IntegrationCatalog {

    private IntegrationCatalog() {
    }

    record IntegrationDef(String key, String name, String category, String note) {
    }

    /** In the exact order {@code INTEGRATIONS} lists them. */
    static final List<IntegrationDef> INTEGRATIONS = List.of(
            new IntegrationDef("p21", "Epicor Prophet 21", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("eclipse", "Epicor Eclipse", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("sxe", "Infor SX.e", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("d365", "Microsoft Dynamics 365", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("netsuite", "NetSuite", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("sap", "SAP", "ERP", "Sales history, costs, item master"),
            new IntegrationDef("salesforce", "Salesforce", "CRM", "Accounts, quotes, SLAs"),
            new IntegrationDef("hubspot", "HubSpot", "CRM", "Accounts, quotes"),
            new IntegrationDef("quickbooks", "QuickBooks", "Accounting", "Invoices, payment terms"),
            new IntegrationDef("xero", "Xero", "Accounting", "Invoices, payment terms"),
            new IntegrationDef("wms", "Warehouse management", "Inventory", "Stock on hand, lots, receipts"),
            new IntegrationDef("oms", "Order management", "Order management", "Open orders, promised dates"),
            new IntegrationDef("edi", "Supplier EDI / portals", "Supplier systems",
                    "Quotes, confirmations, ship notices"),
            new IntegrationDef("api", "REST API", "API", "Read recommendations and outcomes programmatically"),
            new IntegrationDef("webhooks", "Webhooks", "Webhooks",
                    "Push price changes and supplier alerts to your systems"));

    record McpClientDef(String key, String name, String note) {
    }

    static final List<McpClientDef> MCP_CLIENTS = List.of(
            new McpClientDef("claude", "Claude", "Anthropic"),
            new McpClientDef("gemini", "Gemini", "Google"),
            new McpClientDef("other", "Other MCP-compatible tools", "Any client that speaks the protocol"));

    record McpPermissionDef(String key, String group, String label, boolean requiresApproval, boolean defaultOn) {
    }

    /** In the exact order {@code MCP_PERMISSIONS} lists them: read, then actions, then restricted. */
    static final List<McpPermissionDef> MCP_PERMISSIONS = List.of(
            new McpPermissionDef("read-products", "read", "Products", false, true),
            new McpPermissionDef("read-pricing", "read", "Pricing", false, true),
            new McpPermissionDef("read-market", "read", "Market data", false, true),
            new McpPermissionDef("read-suppliers", "read", "Suppliers", false, true),
            new McpPermissionDef("read-orders", "read", "Orders", false, false),
            new McpPermissionDef("read-inventory", "read", "Inventory", false, true),
            new McpPermissionDef("read-reports", "read", "Reports", false, true),
            new McpPermissionDef("act-recommend", "actions", "Create a recommendation", false, true),
            new McpPermissionDef("act-purchase-request", "actions", "Prepare a purchase request", false, true),
            new McpPermissionDef("act-price-update", "actions", "Prepare a price update", false, true),
            new McpPermissionDef("act-report", "actions", "Generate a report", false, true),
            new McpPermissionDef("act-negotiation", "actions", "Prepare a negotiation", false, false),
            new McpPermissionDef("restricted-apply-price", "restricted", "Apply a price", true, false),
            new McpPermissionDef("restricted-place-po", "restricted", "Place a purchase order", true, false),
            new McpPermissionDef("restricted-modify-supplier", "restricted", "Modify a supplier", true, false),
            new McpPermissionDef("restricted-rules", "restricted", "Change business rules", true, false));

    record GroupLabel(String title, String blurb) {
    }

    static final Map<String, GroupLabel> GROUP_LABEL = Map.of(
            "read", new GroupLabel("Read", "What the assistant can look at."),
            "actions", new GroupLabel("Actions",
                    "What it can prepare for you. Nothing here changes the business on its own."),
            "restricted", new GroupLabel("Restricted", "Every use needs a named person to approve it."));

    /** The endpoint an assistant would be pointed at. Illustrative, ported verbatim. */
    static final String MCP_ENDPOINT = "https://aatlas-ai.vercel.app/mcp";

    static IntegrationDef integration(String key) {
        return INTEGRATIONS.stream().filter(i -> i.key().equals(key)).findFirst().orElse(null);
    }

    static McpClientDef mcpClient(String key) {
        return MCP_CLIENTS.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
    }

    static McpPermissionDef permission(String key) {
        return MCP_PERMISSIONS.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }
}

package com.aatlas.integrations.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MCP: the endpoint, which clients are connected, and what they may do. */
@RestController
@RequestMapping(path = "/api/v1/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP", description = "The Model Context Protocol connection: clients and permissions.")
class McpController {

    private final IntegrationsService service;

    McpController(IntegrationsService service) {
        this.service = service;
    }

    @Operation(summary = "The endpoint, the three client kinds, and connection state")
    @GetMapping
    IntegrationsDtos.McpView mcp() {
        return service.mcp();
    }

    @Operation(summary = "Connect a client")
    @PostMapping("/clients/{key}/connect")
    IntegrationsDtos.McpClientView connect(@PathVariable String key) {
        return service.connectMcpClient(key);
    }

    @Operation(summary = "Disconnect a client")
    @DeleteMapping("/clients/{key}/connect")
    ResponseEntity<Void> disconnect(@PathVariable String key) {
        service.disconnectMcpClient(key);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "The 16 permissions, grouped, with this tenant's state")
    @GetMapping("/permissions")
    IntegrationsDtos.McpPermissionsView permissions() {
        return service.permissions();
    }

    @Operation(summary = "Turn permissions on or off",
            description = "Restricted permissions still require a named approver for every use "
                    + "(requiresApproval); this only turns the assistant's access to the permission on or off.")
    @PutMapping(path = "/permissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    IntegrationsDtos.McpPermissionsView update(@RequestBody IntegrationsDtos.UpdatePermissionsRequest request) {
        return service.updatePermissions(request.permissions());
    }
}

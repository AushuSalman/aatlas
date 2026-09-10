package com.aatlas.integrations.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Business systems: the catalogue, and whether this tenant has connected each one. */
@RestController
@RequestMapping(path = "/api/v1/integrations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Integrations", description = "The 15 catalogued business systems and this tenant's connection state.")
class IntegrationsController {

    private final IntegrationsService service;

    IntegrationsController(IntegrationsService service) {
        this.service = service;
    }

    @Operation(summary = "The catalogue with connection state")
    @GetMapping
    List<IntegrationsDtos.IntegrationView> list() {
        return service.integrations();
    }

    @Operation(summary = "Connect a system",
            description = "Idempotent. `config` is stored plain, not encrypted at rest - demo config, "
                    + "not a live secret (see docs/decisions.md).")
    @PostMapping(path = "/{key}/connect", consumes = MediaType.APPLICATION_JSON_VALUE)
    IntegrationsDtos.IntegrationView connect(@PathVariable String key,
            @RequestBody(required = false) IntegrationsDtos.ConnectIntegrationRequest request) {
        Map<String, Object> config = request == null ? Map.of() : request.config();
        return service.connect(key, config);
    }

    @Operation(summary = "Disconnect a system")
    @DeleteMapping("/{key}/connect")
    ResponseEntity<Void> disconnect(@PathVariable String key) {
        service.disconnect(key);
        return ResponseEntity.noContent().build();
    }
}

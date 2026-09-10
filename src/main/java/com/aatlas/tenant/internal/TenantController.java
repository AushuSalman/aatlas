package com.aatlas.tenant.internal;

import com.aatlas.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The company and its settings. The tenant is the JWT's; there is no id in the path. */
@RestController
@RequestMapping(path = "/api/v1/tenant", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Tenant", description = "Company profile, country and trading currency.")
class TenantController {

    private final TenantService service;

    TenantController(TenantService service) {
        this.service = service;
    }

    @Operation(summary = "Company profile")
    @GetMapping
    TenantView tenant() {
        return TenantView.of(service.get(TenantContext.requireTenantId()));
    }

    @Operation(summary = "Rename the company", description = "Heads of sales and purchasing and the commercial director.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Renamed."),
        @ApiResponse(responseCode = "403", description = "This seat may not rename the company (code not_allowed).")
    })
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    TenantView rename(@Valid @RequestBody RenameTenantRequest request) {
        TenantContext.Actor actor = actor();
        return TenantView.of(service.rename(actor.tenantId(), actor, request.name()));
    }

    @Operation(summary = "Country, trading currency and the region nouns")
    @GetMapping("/settings")
    TenantSettingsView settings() {
        return service.settings(TenantContext.requireTenantId());
    }

    @Operation(summary = "Change country or currency",
            description = "A country without a currency picks that country's default. Labels change; prices do not.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved."),
        @ApiResponse(responseCode = "400", description = "Unknown country or unsupported currency.")
    })
    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    TenantSettingsView updateSettings(@Valid @RequestBody TenantSettingsRequest request) {
        TenantContext.Actor actor = actor();
        return service.updateSettings(actor.tenantId(), actor, request.country(), request.currency());
    }

    private static TenantContext.Actor actor() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound"));
    }
}

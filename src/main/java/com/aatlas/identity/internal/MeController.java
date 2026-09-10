package com.aatlas.identity.internal;

import com.aatlas.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person. The first call the shell makes with a token. */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Me", description = "User, company, persona and data source state.")
class MeController {

    private final MeService service;

    MeController(MeService service) {
        this.service = service;
    }

    @Operation(summary = "User, tenant, persona (modules, bulk, guardrails, approve limit), data source state")
    @GetMapping
    MeResponse me() {
        return service.me(TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound")));
    }
}

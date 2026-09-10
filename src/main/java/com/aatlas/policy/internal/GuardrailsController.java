package com.aatlas.policy.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pricing guardrails: the organisation's policy, which every recommendation respects.
 *
 * <p>Thin by rule. The tenant and the actor come from the JWT via {@link TenantContext};
 * whether the actor may write is the service's decision, made from {@code role_policy}.
 */
@RestController
@RequestMapping(path = "/api/v1/guardrails", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Guardrails", description = "Margin floor, discount, speed premium and market ceilings.")
class GuardrailsController {

    private final GuardrailsService service;

    GuardrailsController(GuardrailsService service) {
        this.service = service;
    }

    @Operation(summary = "The four limits, or the platform defaults if none were saved")
    @GetMapping
    GuardrailsView current() {
        return service.current(TenantContext.requireTenantId());
    }

    @Operation(summary = "Save all four limits",
            description = "Only seats whose persona has guardrails=true: heads of sales and purchasing, "
                    + "finance and the commercial director. Writes a history entry and invalidates sell caches.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved."),
        @ApiResponse(responseCode = "400", description = "A value is outside its range; see fields."),
        @ApiResponse(responseCode = "403", description = "This seat may not change guardrails (code not_allowed).")
    })
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    GuardrailsView save(@Valid @RequestBody GuardrailsRequest request) {
        TenantContext.Actor actor = actor();
        return service.save(actor.tenantId(), actor, request.toValues());
    }

    @Operation(summary = "Back to the platform defaults")
    @PostMapping("/reset")
    GuardrailsView reset() {
        TenantContext.Actor actor = actor();
        return service.reset(actor.tenantId(), actor);
    }

    @Operation(summary = "Who changed what, when; newest first")
    @GetMapping("/history")
    CursorPage<GuardrailHistoryView> history(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.history(TenantContext.requireTenantId(), limit, cursor);
    }

    private static TenantContext.Actor actor() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound"));
    }
}

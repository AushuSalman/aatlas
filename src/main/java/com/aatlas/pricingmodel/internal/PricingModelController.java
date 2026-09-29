package com.aatlas.pricingmodel.internal;

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
 * The pricing model: which steps of the recommendation chain run for this tenant and
 * what their knobs are set to.
 *
 * <p>Thin by rule. The tenant and the actor come from the JWT via {@link TenantContext};
 * whether the actor may write is the service's decision, made from {@code role_policy}.
 */
@RestController
@RequestMapping(path = "/api/v1/pricing-model", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Pricing model", description = "The steps and knobs of the recommendation chain, per tenant.")
class PricingModelController {

    private final PricingModelService service;

    PricingModelController(PricingModelService service) {
        this.service = service;
    }

    @Operation(summary = "The registry with this tenant's effective settings and overrides",
            description = "Every parameter with its default and range, the effective setting for each key, and "
                    + "only the overrides that differ from the defaults. A tenant that never saved is on the defaults.")
    @GetMapping
    PricingModelView current() {
        return service.current(TenantContext.requireTenantId());
    }

    @Operation(summary = "Save the settings",
            description = "Only seats whose persona has guardrails=true: heads of sales and purchasing, "
                    + "finance and the commercial director. Settings equal to the default are not stored. "
                    + "Writes a history entry; the next recommendation reads the new model.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved."),
        @ApiResponse(responseCode = "400", description = "An unknown key, the wrong field for a parameter's type, "
                + "or a number outside its range; see fields."),
        @ApiResponse(responseCode = "403", description = "This seat may not change the pricing model (code not_allowed).")
    })
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    PricingModelView save(@Valid @RequestBody PricingModelRequest request) {
        TenantContext.Actor actor = actor();
        return service.save(actor.tenantId(), actor, request.settings());
    }

    @Operation(summary = "Back to the registry defaults")
    @PostMapping("/reset")
    PricingModelView reset() {
        TenantContext.Actor actor = actor();
        return service.reset(actor.tenantId(), actor);
    }

    @Operation(summary = "Who changed what, when; newest first")
    @GetMapping("/history")
    CursorPage<PricingModelHistoryView> history(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.history(TenantContext.requireTenantId(), limit, cursor);
    }

    @Operation(summary = "What the model has learned from this tenant's decisions",
            description = "The tenant-wide lean, the follow rate, the bulk strategy habit and the newest decisions "
                    + "in the model's look-back window.")
    @GetMapping("/learning")
    LearningView learning() {
        return service.learning(TenantContext.requireTenantId());
    }

    private static TenantContext.Actor actor() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound"));
    }
}

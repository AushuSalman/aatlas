package com.aatlas.pricingmodel.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * what their knobs are set to, on either side - the sell model (the price to charge) or
 * the buying model (the cost to aim for). Every endpoint takes {@code side}; without it,
 * the sell model, as before the buying model existed.
 *
 * <p>Thin by rule. The tenant and the actor come from the JWT via {@link TenantContext};
 * whether the actor may write is the service's decision, made from {@code role_policy}.
 */
@RestController
@RequestMapping(path = "/api/v1/pricing-model", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Pricing model", description = "The steps and knobs of the recommendation chains, per tenant, per side.")
class PricingModelController {

    private static final String SIDE_DOC = "Which side of the model: sell (the price to charge; the default when "
            + "omitted) or buy (the cost to aim for). Anything else is 400 validation_failed on the side field.";

    private final PricingModelService service;

    PricingModelController(PricingModelService service) {
        this.service = service;
    }

    @Operation(summary = "One side's registry with this tenant's effective settings and overrides",
            description = "Every parameter of that side with its default and range, the effective setting for each key, "
                    + "and only the overrides that differ from the defaults. A tenant that never saved is on the "
                    + "defaults.")
    @GetMapping
    PricingModelView current(
            @Parameter(description = SIDE_DOC, schema = @Schema(allowableValues = {"sell", "buy"}, defaultValue = "sell"))
                    @RequestParam(required = false) String side) {
        return service.current(TenantContext.requireTenantId(), PricingModelService.sideOf(side));
    }

    @Operation(summary = "Save one side's settings",
            description = "Only seats whose persona has guardrails=true: heads of sales and purchasing, "
                    + "finance and the commercial director. The map is that side's whole settings: what is sent is "
                    + "what the side becomes, the other side is kept as stored, and settings equal to the default are "
                    + "not stored. Writes a history entry; the next recommendation reads the new model.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saved."),
        @ApiResponse(responseCode = "400", description = "An unknown key, a key of the other side, the wrong field "
                + "for a parameter's type, a number outside its range, or an unknown side; see fields."),
        @ApiResponse(responseCode = "403", description = "This seat may not change the pricing model (code not_allowed).")
    })
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    PricingModelView save(
            @Parameter(description = SIDE_DOC, schema = @Schema(allowableValues = {"sell", "buy"}, defaultValue = "sell"))
                    @RequestParam(required = false) String side,
            @Valid @RequestBody PricingModelRequest request) {
        TenantContext.Actor actor = actor();
        return service.save(actor.tenantId(), actor, PricingModelService.sideOf(side), request.settings());
    }

    @Operation(summary = "One side back to the registry defaults",
            description = "Clears that side's overrides only; the other side is kept as stored.")
    @PostMapping("/reset")
    PricingModelView reset(
            @Parameter(description = SIDE_DOC, schema = @Schema(allowableValues = {"sell", "buy"}, defaultValue = "sell"))
                    @RequestParam(required = false) String side) {
        TenantContext.Actor actor = actor();
        return service.reset(actor.tenantId(), actor, PricingModelService.sideOf(side));
    }

    @Operation(summary = "Who changed what, when; newest first",
            description = "Every save or reset of the tenant's model, each entry's snapshot filtered to the side asked "
                    + "for. A save on the other side shows as an entry whose snapshot on this side did not move.")
    @GetMapping("/history")
    CursorPage<PricingModelHistoryView> history(
            @Parameter(description = SIDE_DOC, schema = @Schema(allowableValues = {"sell", "buy"}, defaultValue = "sell"))
                    @RequestParam(required = false) String side,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.history(TenantContext.requireTenantId(), PricingModelService.sideOf(side), limit, cursor);
    }

    @Operation(summary = "What one side of the model has learned from this tenant's decisions",
            description = "The tenant-wide lean, the follow rate, the bulk strategy habit and the newest decisions "
                    + "in that side's look-back window. The sell side learns from prices applied against a suggestion, "
                    + "the buy side from costs agreed against a target.")
    @GetMapping("/learning")
    LearningView learning(
            @Parameter(description = SIDE_DOC, schema = @Schema(allowableValues = {"sell", "buy"}, defaultValue = "sell"))
                    @RequestParam(required = false) String side) {
        return service.learning(TenantContext.requireTenantId(), PricingModelService.sideOf(side));
    }

    private static TenantContext.Actor actor() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException("No actor bound"));
    }
}

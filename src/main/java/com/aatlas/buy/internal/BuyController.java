package com.aatlas.buy.internal;

import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.BuySelectResult;
import com.aatlas.buy.BuyWhatIfResult;
import com.aatlas.buy.Negotiation;
import com.aatlas.buy.ProcurementPlan;
import com.aatlas.buy.SupplierEval;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The buy screens: the landed-cost panel, the fuller evaluation across the whole supplier
 * panel, the side-by-side compare, the procurement plan and its what-ifs, the negotiation
 * letter, and committing to an option.
 *
 * <p>Query parameter names match {@code buyApi} in the frontend's {@code
 * platform/backend.ts} exactly - that file is the contract every wave-2 backend track was
 * given, field for field.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/buy", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Buy", description = "Landed cost, buy intel, procurement plan, negotiation and decisions.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "not_found: no such item number, branch or region.")
})
class BuyController {

    private final BuyService service;

    BuyController(BuyService service) {
        this.service = service;
    }

    @Operation(summary = "The landed-cost panel",
            description = "Ex-works plus freight and duty for one supplier (the incumbent, or `supplier` as an "
                    + "override) into one branch, ranked against the rest of the panel. `buildBuyRecommendation`.")
    @GetMapping("/recommendation")
    BuyRecommendation recommendation(
            @RequestParam @NotBlank String item,
            @RequestParam @NotBlank String destination,
            @RequestParam(required = false) String supplier) {
        return service.recommendation(item, destination, supplier);
    }

    @Operation(summary = "Buy intelligence for one order",
            description = "Effective cost per supplier (landed plus reliability, quality, terms), now-vs-wait, "
                    + "and the negotiation letter. `getBuyIntel`. `destination` defaults to the region's busiest "
                    + "branch when omitted.")
    @GetMapping("/intel")
    BuyIntel intel(
            @RequestParam @NotBlank String item,
            @RequestParam @NotBlank String region,
            @RequestParam @Min(1) int qty,
            @RequestParam(required = false) String destination) {
        return service.intel(item, region, qty, destination);
    }

    @Operation(summary = "The supplier panel, evaluated and ranked",
            description = "`BuyIntel.suppliers` alone - the side-by-side compare's rows.")
    @GetMapping("/compare")
    List<SupplierEval> compare(
            @RequestParam @NotBlank String item,
            @RequestParam @NotBlank String region,
            @RequestParam @Min(1) int qty,
            @RequestParam(required = false) String destination) {
        return service.compare(item, region, qty, destination);
    }

    @Operation(summary = "The procurement decision for one order",
            description = "Options A-D, ranked suppliers, trade-offs, a decision score and a cost/speed matrix, "
                    + "weighted by `priority` and how urgent `requiredDays` makes the order. `procurementPlan`.")
    @GetMapping("/plan")
    ProcurementPlan plan(
            @RequestParam @NotBlank String item,
            @RequestParam @NotBlank String region,
            @RequestParam @Min(1) int qty,
            @RequestParam @Min(1) int requiredDays,
            @RequestParam @NotBlank String priority,
            @RequestParam(required = false) String destination) {
        return service.plan(item, region, qty, requiredDays, priority, destination);
    }

    @Operation(summary = "What if: one of the four named scenarios against the plan's recommendation",
            description = "Supplier raises price 3%, delivery slips 7 days, the order is split, or paying for "
                    + "speed. `buyWhatIf`.")
    @PostMapping(path = "/what-if", consumes = MediaType.APPLICATION_JSON_VALUE)
    BuyWhatIfResult whatIf(@Valid @RequestBody BuyWhatIfRequest request) {
        return service.whatIf(request.item(), request.region(), request.qty(), request.requiredDays(),
                request.priority(), request.scenario());
    }

    @Operation(summary = "The negotiation letter and levers",
            description = "`BuyIntel.negotiation` alone.")
    @GetMapping("/negotiation")
    Negotiation negotiation(
            @RequestParam @NotBlank String item,
            @RequestParam @NotBlank String region,
            @RequestParam @Min(1) int qty,
            @RequestParam(required = false) String destination) {
        return service.negotiation(item, region, qty, destination);
    }

    @Operation(summary = "Commit to one procurement option",
            description = "Writes a decision and a purchase, or - over the signed-in seat's approval limit "
                    + "(`Persona.approveLimit`) - an approval request. `ok:true` when it went straight through; "
                    + "`pending:true` with `limit`/`approver` when it needs a sign-off.")
    @PostMapping(path = "/select", consumes = MediaType.APPLICATION_JSON_VALUE)
    BuySelectResult select(@Valid @RequestBody BuySelectRequest request) {
        return service.select(request);
    }
}

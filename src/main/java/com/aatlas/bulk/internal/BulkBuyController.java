package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk buy: award a basket into one region five ways, and apply one.
 *
 * <p>Every figure is computed on the request thread from {@link BulkBuyEngine} - no
 * snapshot table, per the wave-2 brief. Thin by rule: reads the request, hands it to
 * {@link BulkBuyService}.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/buy/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bulk buy", description = "Basket awards into one region: lowest cost, fastest, lowest risk, balanced, split.")
class BulkBuyController {

    private final BulkBuyService service;

    BulkBuyController(BulkBuyService service) {
        this.service = service;
    }

    @Operation(summary = "Award a basket five ways",
            description = "Every priceable item in `items` into `region`, with five strategies, their "
                    + "awards, fulfilment and supplier dependency. `horizon` scales the quarter's volume "
                    + "each line buys: 1 = quarter, 2 = half year, 4 = year.")
    @GetMapping("/plan")
    BulkBuyDtos.PlanView plan(
            @Parameter(example = "south") @RequestParam @NotBlank String region,
            @Parameter(description = "Comma-separated item numbers.", example = "HRD118902,HRD304148")
                    @RequestParam @NotBlank String items,
            @Parameter(example = "1") @RequestParam(defaultValue = "1") @Positive double horizon) {
        return service.plan(region, BulkSellController.splitItems(items), horizon);
    }

    @Operation(summary = "Apply an award strategy",
            description = "Recomputes the plan and applies one strategy: the bulk plan plus one decision "
                    + "(see docs/decisions.md for the stand-in this writes through). Only seats whose "
                    + "persona has `bulk=true`.")
    @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not apply a bulk strategy.")
    @ApiResponse(responseCode = "400", description = "unknown_strategy: strategyKey is not one of the plan's five.")
    @PostMapping(path = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApplyBuyResponse apply(@Valid @RequestBody ApplyBuyStrategyRequest request) {
        return service.apply(request);
    }
}

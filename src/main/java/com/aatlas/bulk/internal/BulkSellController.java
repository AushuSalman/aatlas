package com.aatlas.bulk.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
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
 * Bulk sell: price a basket at one store three ways, and apply one.
 *
 * <p>Every figure is computed on the request thread from {@link BulkSellEngine} - no
 * snapshot table, per the wave-2 brief. Thin by rule: reads the request, hands it to
 * {@link BulkSellService}.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/sell/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bulk sell", description = "Basket pricing at one store: max profit, fast movement, balanced.")
class BulkSellController {

    private final BulkSellService service;

    BulkSellController(BulkSellService service) {
        this.service = service;
    }

    @Operation(summary = "Price a basket three ways",
            description = "Every priceable item in `items` at `store`, with three strategies, their "
                    + "projected revenue/profit/margin/turnover, and line prices.")
    @GetMapping("/plan")
    BulkSellDtos.PlanView plan(
            @Parameter(example = "100959") @RequestParam @NotBlank String store,
            @Parameter(description = "Comma-separated item numbers.", example = "HRD118902,HRD304148")
                    @RequestParam @NotBlank String items) {
        return service.plan(store, splitItems(items));
    }

    @Operation(summary = "Apply a strategy",
            description = "Recomputes the plan and applies one strategy: a deal per line plus one bulk "
                    + "decision (see docs/decisions.md for the stand-in this writes through). Only seats "
                    + "whose persona has `bulk=true`.")
    @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not apply a bulk strategy.")
    @ApiResponse(responseCode = "400", description = "unknown_strategy: strategyKey is not one of the plan's three.")
    @PostMapping(path = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApplySellResponse apply(@Valid @RequestBody ApplySellStrategyRequest request) {
        return service.apply(request);
    }

    static List<String> splitItems(String items) {
        return Arrays.stream(items.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

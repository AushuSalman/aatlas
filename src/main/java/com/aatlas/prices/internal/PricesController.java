package com.aatlas.prices.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Set your prices": suggestions for items with no price, the bulk apply and its undo, and
 * one item's price list. Thin by rule: every call is one {@link PricesService} method.
 */
@RestController
@RequestMapping(path = "/api/v1/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Prices", description = "Suggested list prices, the bulk apply and the price list per item.")
class PricesController {

    private final PricesService service;
    private final MarketPriceService marketPrice;

    PricesController(PricesService service, MarketPriceService marketPrice) {
        this.service = service;
        this.marketPrice = marketPrice;
    }

    @Operation(summary = "Suggested list prices, with the basis of each")
    @GetMapping("/suggestions")
    SuggestionsResponse suggestions(
            @RequestParam(required = false, defaultValue = "missing") String scope,
            @RequestParam(required = false) String store,
            @RequestParam(required = false) String margins) {
        return service.suggestions(scope, store, margins);
    }

    @Operation(summary = "Apply suggested or entered prices in one write")
    @PostMapping(path = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE)
    BulkPriceResponse bulk(@Valid @RequestBody BulkPriceRequest request) {
        return service.bulk(request);
    }

    @Operation(summary = "Undo a bulk write that nothing newer has superseded")
    @DeleteMapping("/bulk/{writeId}")
    UndoResponse undo(@PathVariable UUID writeId) {
        return service.undo(writeId);
    }

    @Operation(summary = "One item's current price and cost, the ladder, and the rows behind them")
    @GetMapping("/{item}")
    PriceDetail detail(@PathVariable String item, @RequestParam(required = false) String store) {
        return service.detail(item, store);
    }

    @Operation(summary = "Set one item's list price and/or cost")
    @PutMapping(path = "/{item}", consumes = MediaType.APPLICATION_JSON_VALUE)
    PriceDetail set(@PathVariable String item, @Valid @RequestBody SetPriceRequest request) {
        return service.set(item, request);
    }

    @Operation(summary = "Whether AI market research is configured", description = "False when no search provider key is set.")
    @GetMapping("/market-research/available")
    java.util.Map<String, Boolean> marketResearchAvailable() {
        return java.util.Map.of("available", marketPrice.available());
    }

    @Operation(summary = "Search the open web for what this item sells for",
            description = "An AI-synthesised summary of public search results, read for a price. Not your "
                    + "own data, not verified - a starting point, always shown with its sources.")
    @GetMapping("/{item}/market-research")
    MarketPriceView marketResearch(@PathVariable String item) {
        return marketPrice.research(item);
    }
}

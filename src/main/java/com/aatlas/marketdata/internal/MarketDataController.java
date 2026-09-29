package com.aatlas.marketdata.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The live reference feeds: what they last said, and a manual refresh. */
@RestController
@RequestMapping(path = "/api/v1/market-data", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Market data", description = "Commodity index moves (FRED) and exchange rates (ECB via Frankfurter).")
class MarketDataController {

    private final MarketDataService service;

    MarketDataController(MarketDataService service) {
        this.service = service;
    }

    @Operation(summary = "Every commodity move and exchange rate the engines read, with its source and date")
    @GetMapping("/status")
    MarketDataService.Status status() {
        return service.status();
    }

    @Operation(summary = "Refresh commodity moves and exchange rates now",
            description = "Runs daily on its own; a call within ten minutes of the last run returns that run's report.")
    @PostMapping("/refresh")
    MarketDataService.RunReport refresh() {
        return service.refreshNow();
    }
}

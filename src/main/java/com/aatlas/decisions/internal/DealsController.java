package com.aatlas.decisions.internal;

import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recorded deals: the seeded 181 plus any this workspace has recorded, newest first. */
@RestController
@RequestMapping(path = "/api/v1/deals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Deals", description = "Closed sales and purchases, seeded and live-recorded.")
class DealsController {

    private final HistoryService service;

    DealsController(HistoryService service) {
        this.service = service;
    }

    @Operation(summary = "Recorded deals, newest first, keyset paged")
    @GetMapping
    CursorPage<DealRecord> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return service.pagedDeals(limit, cursor);
    }
}

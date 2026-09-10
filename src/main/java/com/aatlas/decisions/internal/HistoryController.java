package com.aatlas.decisions.internal;

import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The History screen: every recommendation, what was applied, what actually happened. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "History", description = "Decision history and its summary figures.")
class HistoryController {

    private final HistoryService service;

    HistoryController(HistoryService service) {
        this.service = service;
    }

    @Operation(summary = "History rows, filterable and keyset paged")
    @GetMapping("/history")
    CursorPage<HistoryRow> history(
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String item,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "date") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return service.history(side, item, q, sort, cursor, limit);
    }

    @Operation(summary = "decisions / followed% / gained / lost / net")
    @GetMapping("/history/summary")
    HistorySummaryView summary() {
        return service.summary();
    }
}

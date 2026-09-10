package com.aatlas.decisions.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sell + buy impact summaries by month - what the tool has been worth. */
@RestController
@RequestMapping(path = "/api/v1/impact", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Impact", description = "Sell and buy impact rollup, by month.")
class ImpactController {

    private final HistoryService service;

    ImpactController(HistoryService service) {
        this.service = service;
    }

    @Operation(summary = "Sell + buy impact summaries by month")
    @GetMapping
    ImpactData impact() {
        return service.buildImpact();
    }
}

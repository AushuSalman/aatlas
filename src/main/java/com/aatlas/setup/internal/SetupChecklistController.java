package com.aatlas.setup.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What this workspace still has to load. Read by the notifications bell. */
@RestController
@RequestMapping(path = "/api/v1/setup", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Setup", description = "Workspace setup checklist: which data is loaded and which is missing.")
class SetupChecklistController {

    private final SetupChecklistService service;

    SetupChecklistController(SetupChecklistService service) {
        this.service = service;
    }

    @Operation(summary = "Setup checklist",
            description = "One line per kind of data (sales, products, suppliers, purchases, competitor prices, stock, "
                    + "branch regions, team), whether it is loaded, and the screen that completes it.")
    @GetMapping("/checklist")
    SetupChecklistService.Checklist checklist() {
        return service.checklist();
    }
}

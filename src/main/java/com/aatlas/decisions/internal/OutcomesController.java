package com.aatlas.decisions.internal;

import com.aatlas.decisions.DecisionOutcomes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What applied prices actually did, and a manual run of the nightly measurement. */
@RestController
@RequestMapping(path = "/api/v1/decisions/outcomes", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Decision outcomes", description = "Sales before against after each applied price; trains the pricing model.")
class OutcomesController {

    private final OutcomesService service;

    OutcomesController(OutcomesService service) {
        this.service = service;
    }

    @Operation(summary = "Every measured decision's outcome, newest first")
    @GetMapping
    Collection<DecisionOutcomes.Outcome> list() {
        return service.list();
    }

    @Operation(summary = "Measure every decision whose after-window has closed, now",
            description = "The same run the nightly job does, for this workspace only.")
    @PostMapping("/measure")
    OutcomeMeasurer.RunSummary measure() {
        return service.measureNow();
    }
}

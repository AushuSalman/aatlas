package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the workspace has, and what each file would unlock. Never gated on a data source. */
@RestController
@RequestMapping(path = "/api/v1/workspace", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Workspace", description = "Data readiness: counts, feature status, next steps.")
class ReadinessController {

    private final ReadinessService readiness;

    ReadinessController(ReadinessService readiness) {
        this.readiness = readiness;
    }

    @Operation(summary = "Data readiness",
            description = """
                    Counts over the tenant's own rows, a status per feature (`ready`, `partial`, \
                    `locked` with the kinds it `needs`), up to three next steps, and the progress of a \
                    sample load. Poll while `sampleLoading.active` is true.
                    """)
    @GetMapping("/readiness")
    ReadinessView readiness() {
        return readiness.readiness(TenantContext.requireTenantId());
    }
}

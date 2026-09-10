package com.aatlas.catalog.internal;

import com.aatlas.catalog.internal.reference.ReferenceDataRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reference data that is the same for everyone and safe to cache anywhere: the
 * logistics rate card. Public - it is listed in {@code SecurityConfig.PUBLIC} - because it
 * carries nothing about any tenant and the client wants it before sign-in completes.
 */
@RestController
@RequestMapping(path = "/api/v1/reference", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reference", description = "Data identical for every tenant. No token required.")
class ReferenceController {

    private final CatalogService catalog;

    ReferenceController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "The logistics rate card",
            description = "`regions` (inland haul from each point of entry) and `origins` (inbound freight "
                    + "and duty by origin country), verbatim from the reference tables. Stated rates, not a model.")
    @SecurityRequirements
    @GetMapping("/logistics")
    ResponseEntity<ReferenceDataRepository.Logistics> logistics() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(catalog.logistics());
    }
}

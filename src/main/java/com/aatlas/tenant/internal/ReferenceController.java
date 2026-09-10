package com.aatlas.tenant.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Reference data: the same for every tenant, needed before sign-in (the signup form's
 * country picker), so public and cacheable. {@code SecurityConfig.PUBLIC} opens the path.
 */
@RestController
@RequestMapping(path = "/api/v1/reference", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Reference", description = "Countries and currencies. No token required.")
class ReferenceController {

    private final ReferenceData reference;
    private final ReferenceService service;

    ReferenceController(ReferenceData reference, ReferenceService service) {
        this.reference = reference;
        this.service = service;
    }

    @Operation(summary = "Countries with regions and subdivisions",
            description = "seed/countries.json byte for byte, with an ETag; send If-None-Match to get a 304.")
    @GetMapping("/countries")
    ResponseEntity<byte[]> countries(WebRequest request) {
        String etag = reference.countriesEtag();
        if (request.checkNotModified(etag)) {
            // checkNotModified has already written the 304 and the validators.
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reference.countriesJson());
    }

    @Operation(summary = "Currencies with the current rate and as-of date")
    @GetMapping("/currencies")
    ResponseEntity<CurrenciesView> currencies() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(service.currencies());
    }
}

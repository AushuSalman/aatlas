package com.aatlas.suppliers.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import com.aatlas.suppliers.SupplierPanelSeeder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The supplier panel: who is on it, their terms, rating, reviews, fulfilment risk and
 * performance history, plus "pull their information from the web" and adding what was found.
 *
 * <p>Thin by rule: reads a request, hands it to {@link SuppliersService} or
 * {@link SupplierPanelSeeder}, shapes the response. Every id in the path is the frontend's
 * supplier id ({@code sup-2}, {@code cus-f26j4f}), never the row's own uuid.
 */
@RestController
@RequestMapping(path = "/api/v1/suppliers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Suppliers", description = "The supplier panel: terms, ratings, reviews, risk and lookup.")
class SuppliersController {

    private final SuppliersService service;
    private final SupplierPanelSeeder seeder;

    SuppliersController(SuppliersService service, SupplierPanelSeeder seeder) {
        this.service = service;
        this.seeder = seeder;
    }

    @Operation(summary = "The panel, ranked by rating")
    @GetMapping
    CursorPage<SupplierProfileView> panel(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        List<SupplierProfileView> all = service.panel();
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id().equals(cursor)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<SupplierProfileView> window = all.subList(start, all.size());
        List<SupplierProfileView> slice = window.stream().limit((long) limit + 1).toList();
        return CursorPage.of(slice, limit, SupplierProfileView::id);
    }

    @Operation(summary = "The panel in numbers")
    @GetMapping("/summary")
    PanelSummary.View summary() {
        return service.summary();
    }

    @Operation(summary = "One supplier, as the Suppliers screen renders it")
    @GetMapping("/{id}")
    SupplierProfileView get(@PathVariable String id) {
        return service.get(id);
    }

    @Operation(summary = "Commercial terms, priced for an order")
    @GetMapping("/{id}/terms")
    TermsResponse terms(@PathVariable String id, @RequestParam(required = false, defaultValue = "0") int qty) {
        return service.terms(id, qty);
    }

    @Operation(summary = "The stars and what they mean")
    @GetMapping("/{id}/rating")
    RatingResponse rating(@PathVariable String id) {
        return service.rating(id);
    }

    @Operation(summary = "What other buyers said")
    @GetMapping("/{id}/reviews")
    List<SupplierReview> reviews(@PathVariable String id) {
        return service.reviewsFor(id);
    }

    @Operation(summary = "Fulfilment risk")
    @GetMapping("/{id}/risk")
    SupplierRisk risk(@PathVariable String id) {
        return service.risk(id);
    }

    @Operation(summary = "The scorecard: on-time trend, spend, tenure")
    @GetMapping("/{id}/performance")
    PerformanceResponse performance(@PathVariable String id) {
        return service.performance(id);
    }

    @Operation(summary = "Pull a supplier's information from the web",
            description = "Deterministic and simulated: the same query always returns the same company. "
                    + "Persisted so adding it to the panel references what was shown rather than a second "
                    + "computation that might differ.")
    @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    LookupResponse lookup(@Valid @RequestBody LookupRequest request) {
        return service.lookup(request);
    }

    @Operation(summary = "Add a looked-up supplier to the panel",
            description = "Buy seats (purchase manager, buyer, purchase head) and the commercial director only.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SupplierProfileView> add(@Valid @RequestBody AddSupplierRequest request,
            UriComponentsBuilder uri) {
        SupplierProfileView profile = service.add(request);
        return ResponseEntity.created(uri.replacePath("/api/v1/suppliers/{id}").build(profile.id()))
                .body(profile);
    }

    @Operation(summary = "Import a list of suppliers onto the panel",
            description = """
                    Takes supplier records, not a file: the client parses its CSV and shows the buyer a
                    dry run, then sends the rows that passed. The server re-validates every one and has
                    the last word.

                    Row by row rather than all or nothing - a panel is a list of independent companies,
                    so one bad row is returned in `rejected` with its index rather than losing the rest.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Added, updated and rejected, split out."),
        @ApiResponse(responseCode = "403", description = "Only buy seats and the director may change the panel.")
    })
    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    ImportSuppliersResponse importSuppliers(@Valid @RequestBody ImportSuppliersRequest request) {
        return service.importSuppliers(request);
    }

    @Operation(summary = "Edit a supplier's contact, category or terms")
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    SupplierProfileView patch(@PathVariable String id, @Valid @RequestBody PatchSupplierRequest request) {
        return service.patch(id, request);
    }

    @Operation(summary = "Remove a supplier from the panel",
            description = "Custom (looked-up) suppliers only; the seeded panel cannot be removed.")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Seed the tenant's starting panel",
            description = "Director only. Idempotent: a tenant that already has its panel gets nothing added. "
                    + "Normally triggered by the sample data source connecting; exposed here so it can be "
                    + "exercised directly.")
    @PostMapping("/seed")
    SeedResponse seed() {
        service.requireDirector();
        int added = seeder.seedForTenant(TenantContext.requireTenantId());
        return new SeedResponse(added);
    }
}

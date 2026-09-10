package com.aatlas.catalog.internal;

import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogue reads: products, branches, regions, accounts. Group F of the blueprint.
 *
 * <p>Thin on purpose: each method reads one page or one row from {@link CatalogService}.
 * The class-level {@code @Validated} is what turns a {@code limit=0} into a 400 with a
 * {@code fields} map rather than a 500.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Catalogue", description = "Products, branches, market regions and accounts for the signed-in tenant.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "no_catalogue: the tenant has not connected a data source.")
})
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    // ---- products ----------------------------------------------------------------------

    @Operation(summary = "Search the catalogue",
            description = "The picker. `q` matches the item number or description, case-insensitive; "
                    + "`hasSales=true` limits to items a price can be produced for.")
    @GetMapping("/products")
    CursorPage<ProductView> products(
            @Parameter(description = "Substring of the item number or description.") @RequestParam(required = false) String q,
            @Parameter(example = "Plumbing") @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean hasSales,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        return catalog.products(q, category, hasSales, limit, cursor);
    }

    @Operation(summary = "One product with its commodity trend and the branches that sell it")
    @ApiResponse(responseCode = "404", description = "not_found: no such item number.")
    @GetMapping("/products/{item}")
    ProductDetailView product(@PathVariable String item) {
        return catalog.product(item);
    }

    @Operation(summary = "Branches with sales history for an item",
            description = "The (item, branch) pairs a price can be produced for. Empty for a PIM-only item.")
    @GetMapping("/products/{item}/stores")
    List<StoreView> productStores(@PathVariable String item) {
        return catalog.productStores(item);
    }

    // ---- stores ------------------------------------------------------------------------

    @Operation(summary = "Branches", description = "With region, RPP and activity, in branch-code order.")
    @GetMapping("/stores")
    CursorPage<StoreView> stores(
            @Parameter(description = "Market region key: south, west, north or east.") @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        return catalog.stores(region, limit, cursor);
    }

    @Operation(summary = "One branch", description = "By branch code (100959) or by our uuid.")
    @ApiResponse(responseCode = "404", description = "not_found: no such branch.")
    @GetMapping("/stores/{id}")
    StoreView store(@PathVariable String id) {
        return catalog.store(id);
    }

    // ---- regions -----------------------------------------------------------------------

    @Operation(summary = "Market regions for this tenant's country",
            description = "The four regions with their subdivisions and the branches in each.")
    @GetMapping("/regions")
    List<RegionView> regions() {
        return catalog.regions();
    }

    // ---- customers ---------------------------------------------------------------------

    @Operation(summary = "Accounts", description = "The quote picker: segment, tier, agreed discount, profile, SLA.")
    @GetMapping("/customers")
    CursorPage<CustomerView> customers(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        return catalog.customers(limit, cursor);
    }

    @Operation(summary = "One account", description = "By code (c-1) or by our uuid.")
    @ApiResponse(responseCode = "404", description = "not_found: no such account.")
    @GetMapping("/customers/{id}")
    CustomerView customer(@PathVariable String id) {
        return catalog.customer(id);
    }
}

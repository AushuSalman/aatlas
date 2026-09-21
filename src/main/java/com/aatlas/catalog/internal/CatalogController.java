package com.aatlas.catalog.internal;

import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
 * Catalogue reads - products, branches, regions, accounts - and the branch list's writes.
 * Group F of the blueprint.
 *
 * <p>Thin on purpose: each method hands one request to {@link CatalogService} or, for the
 * branches, {@link StoreService}, and shapes the response. The class-level
 * {@code @Validated} is what turns a {@code limit=0} into a 400 with a {@code fields} map
 * rather than a 500.
 *
 * <p>The {@code no_catalogue} answer in the class-level responses is a read-side rule.
 * {@code POST /stores} is exempt: a company with no data source connected is exactly the
 * one that needs to type its first branch in.
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
    private final StoreService storeService;
    private final ProductService productService;

    CatalogController(CatalogService catalog, StoreService storeService, ProductService productService) {
        this.catalog = catalog;
        this.storeService = storeService;
        this.productService = productService;
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

    @Operation(summary = "Add a product",
            description = """
                    Heads of sales and purchasing and the commercial director only.

                    One item typed in by hand, under the same rules as a new item in the products import:
                    blank category and subcategory are `uncategorised`, a blank unit is `each`, and a
                    commodity must be one the platform tracks. A `listPrice` or `unitCost` goes to the price
                    list tenant-wide. The item is not priceable (`hasSales=false`) until it has sales history.

                    Like opening a branch, this does not require a connected data source.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created. Location carries the item number."),
        @ApiResponse(responseCode = "400", description = "unknown_commodity, or a field error."),
        @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not add products."),
        @ApiResponse(responseCode = "409", description = "item_number_taken: that item number is in use.")
    })
    @PostMapping(path = "/products", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProductView> createProduct(@Valid @RequestBody CreateProductRequest request, UriComponentsBuilder uri) {
        ProductView product = productService.create(request);
        return ResponseEntity.created(uri.replacePath("/api/v1/products/{item}").build(product.itemNumber())).body(product);
    }

    // ---- stores ------------------------------------------------------------------------

    @Operation(summary = "Branches", description = "With region, RPP and activity, in branch-code order.")
    @GetMapping("/stores")
    CursorPage<StoreView> stores(
            @Parameter(description = "Market region key: south, west, north, east - or unassigned for the "
                    + "branches an import created that nobody has placed yet.")
                    @RequestParam(required = false) String region,
            @Parameter(description = "Substring of the branch code, name or MSA, case-insensitive.")
                    @RequestParam(required = false) String q,
            @Parameter(description = "true for the branches still trading, false for the retired ones. "
                    + "Omit for both.")
                    @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        return storeService.list(region, q, active, limit, cursor);
    }

    @Operation(summary = "One branch", description = "By branch code (100959) or by our uuid.")
    @ApiResponse(responseCode = "404", description = "not_found: no such branch.")
    @GetMapping("/stores/{id}")
    StoreView store(@PathVariable String id) {
        return storeService.get(id);
    }

    @Operation(summary = "Open a branch",
            description = """
                    Heads of sales and purchasing and the commercial director only.

                    The market region is never guessed. Send `state` and it follows from the reference
                    tables; send neither `state` nor `regionKey` and the branch opens `unassigned`, which
                    keeps it out of every regional rollup until somebody places it.

                    Unlike the reads, this does not require a connected data source: typing the first
                    branch in is a legitimate way to start a catalogue.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Created. Location carries the branch code."),
        @ApiResponse(responseCode = "400", description = "unknown_country, unknown_subdivision, "
                + "unknown_region, region_mismatch, country_mismatch, unknown_segment or a field error."),
        @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not change the branch list."),
        @ApiResponse(responseCode = "409", description = "store_code_taken: that branch code is in use.")
    })
    @PostMapping(path = "/stores", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StoreView> createStore(@Valid @RequestBody CreateStoreRequest request, UriComponentsBuilder uri) {
        StoreView store = storeService.create(request);
        return ResponseEntity.created(uri.replacePath("/api/v1/stores/{id}").build(store.storeId())).body(store);
    }

    @Operation(summary = "Correct a branch",
            description = """
                    Every field is optional; only what is sent changes. Placing an imported branch is
                    `{"regionKey": "south"}` on its own, and retiring one is `{"active": false}`.

                    The branch code cannot change - twelve months of transactions and the ERP refer to it
                    as text - so sending a different `store_id` is a 400 rather than a silent no-op.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "400", description = "store_code_immutable, or one of the placement errors."),
        @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not change the branch list."),
        @ApiResponse(responseCode = "404", description = "not_found: no such branch.")
    })
    @PatchMapping(path = "/stores/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    StoreView patchStore(@PathVariable String id, @Valid @RequestBody PatchStoreRequest request) {
        return storeService.patch(id, request);
    }

    @Operation(summary = "Close a branch",
            description = """
                    Refused while the branch has item-at-branch sales history, because the delete would
                    cascade it away and every (item, branch) pair it made priceable would quietly stop
                    being priceable. Retire it with `PATCH {"active": false}` to keep the history, or
                    repeat with `force=true` to delete both.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Gone."),
        @ApiResponse(responseCode = "403", description = "not_allowed: this seat may not change the branch list."),
        @ApiResponse(responseCode = "404", description = "not_found: no such branch."),
        @ApiResponse(responseCode = "409", description = "store_in_use: the branch has sales history.")
    })
    @DeleteMapping("/stores/{id}")
    ResponseEntity<Void> deleteStore(
            @PathVariable String id,
            @Parameter(description = "Delete the branch's sales history with it.")
                    @RequestParam(defaultValue = "false") boolean force) {
        storeService.delete(id, force);
        return ResponseEntity.noContent().build();
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

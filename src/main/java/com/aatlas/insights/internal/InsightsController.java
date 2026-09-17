package com.aatlas.insights.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.insights.internal.DemographicsEngine.Demographics;
import com.aatlas.insights.internal.DemographicsEngine.Filter;
import com.aatlas.insights.internal.GeoEngine.RegionIntel;
import com.aatlas.insights.internal.GeoEngine.StoreIntel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Region intel, branch intel and demographics - the Insights-sell and Stores screens.
 * Backs {@code /app/insights} and {@code /app/stores}. Every figure is read from the
 * tenant's real rows through {@link InsightsDataLoader}.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/insights", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Insights", description = "Regional and branch intelligence, and the demand demographics behind it.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "no_catalogue: the tenant has not connected a data source.")
})
class InsightsController {

    /** {@code south}/{@code west}/{@code north}/{@code east} plus {@code unassigned}: the "Needs a
     * region" bucket is always reachable directly, even though {@code /insights/regions} omits an
     * empty region from its list. */
    private static final Set<String> REGION_KEYS = Set.of("south", "west", "north", "east", "unassigned");

    private final InsightsDataLoader loader;
    private final GeoEngine geoEngine;
    private final DemographicsEngine demographicsEngine;

    InsightsController(InsightsDataLoader loader, GeoEngine geoEngine, DemographicsEngine demographicsEngine) {
        this.loader = loader;
        this.geoEngine = geoEngine;
        this.demographicsEngine = demographicsEngine;
    }

    @Operation(summary = "Every market region with at least one branch",
            description = "The tenant's real regions, with their branches and actions. A region with no branches "
                    + "is omitted here; reach it directly at GET /insights/regions/{key}.")
    @GetMapping("/regions")
    List<RegionIntel> regions() {
        return geoEngine.allRegions(loader.load());
    }

    @Operation(summary = "One market region", description = "With its branches and actions.")
    @ApiResponse(responseCode = "404", description = "not_found: no such region key.")
    @GetMapping("/regions/{key}")
    RegionIntel region(@Parameter(example = "south") @PathVariable String key) {
        String normalised = key == null ? "" : key.strip().toLowerCase(java.util.Locale.ROOT);
        if (!REGION_KEYS.contains(normalised)) {
            throw ApiException.notFound("Region", key);
        }
        return geoEngine.regionIntel(normalised, loader.load());
    }

    @Operation(summary = "Every branch", description = "Backs the Stores screen's list; includes the synthetic "
            + "no-branch row when the tenant has sales with no store on file.")
    @GetMapping("/stores")
    List<StoreIntel> stores() {
        return geoEngine.allStores(loader.load());
    }

    @Operation(summary = "One branch", description = "With its opportunities and priced products.")
    @ApiResponse(responseCode = "404", description = "not_found: no such branch.")
    @GetMapping("/stores/{id}")
    StoreIntel store(@Parameter(example = "100959") @PathVariable String id) {
        InsightsData data = loader.load();
        if (!"no-branch".equals(id) && data.store(id).isEmpty()) {
            throw ApiException.notFound("Store", id);
        }
        return geoEngine.storeIntel(id, data);
    }

    @Operation(summary = "Demographics", description = "Segments, categories, places and origins for the given scope.")
    @GetMapping("/demographics")
    Demographics demographics(
            @RequestParam(defaultValue = "all") String region,
            @RequestParam(defaultValue = "all") String state,
            @RequestParam(required = false) String store,
            @RequestParam(defaultValue = "all") String segment,
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(defaultValue = "12m") String period) {
        InsightsData data = loader.load();
        Filter filter = new Filter(region, state, store, segment, category, period);
        return demographicsEngine.compute(filter, data);
    }

    @Operation(summary = "Biggest expected 90-day price moves in scope")
    @GetMapping("/price-moves")
    List<PriceMovesEngine.PriceMove> priceMoves(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String store,
            @RequestParam(required = false) String category) {
        return PriceMovesEngine.compute(region, store, category, loader.load());
    }
}

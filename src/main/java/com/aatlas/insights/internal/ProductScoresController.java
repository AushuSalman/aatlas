package com.aatlas.insights.internal;

import com.aatlas.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opportunity scores for the Products screen: {@code GET /products/scores} (one row per
 * priceable item-branch pair in scope) and {@code GET /products/{item}/scores} (one
 * product's score at every branch, with reasons). Both return the frontend's
 * {@code ProductScoreRow} shape - see {@link ProductScoresEngine} - and the screen folds
 * them into "best branch per product" itself, exactly as it does today reading the
 * fixtures directly.
 *
 * <p>{@code ScoreEngine} is this module's own port of {@code intel/score.ts} -
 * TODO(merge): replace with the {@code sell} module's canonical {@code OpportunityScores}
 * reader once that track is merged; see {@link ScoreEngine}.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Products", description = "Opportunity-scored products for the Products screen.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "no_catalogue: the tenant has not connected a data source.")
})
class ProductScoresController {

    private final CatalogSnapshotReader reader;

    ProductScoresController(CatalogSnapshotReader reader) {
        this.reader = reader;
    }

    @Operation(summary = "Products by opportunity",
            description = "One row per priceable item-branch pair in scope. "
                    + "`filter` is strong/watch/risk, `sort` is score/trend/opportunity.")
    @GetMapping("/scores")
    List<ProductScoresEngine.ProductScoreRow> scores(
            @Parameter(description = "Market region key, or omitted/`all` for the whole network.") @RequestParam(required = false) String region,
            @Parameter(description = "strong, watch or risk; omitted/`all` for every tier.") @RequestParam(required = false) String filter,
            @Parameter(description = "score (default), trend or opportunity.") @RequestParam(required = false) String sort) {
        CatalogSnapshot snapshot = reader.load();
        return ProductScoresEngine.scores(region, filter, sort, snapshot);
    }

    @Operation(summary = "One product's score at every branch", description = "Unscoped, with the reasons behind each score.")
    @ApiResponse(responseCode = "404", description = "not_found: no such item number, or it has no sales history anywhere.")
    @GetMapping("/{item}/scores")
    List<ProductScoresEngine.ProductScoreRow> productScores(@Parameter(example = "HRD118902") @PathVariable String item) {
        CatalogSnapshot snapshot = reader.load();
        if (snapshot.product(item).isEmpty()) {
            throw ApiException.notFound("Product", item);
        }
        List<ProductScoresEngine.ProductScoreRow> rows = ProductScoresEngine.forItem(item, snapshot);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Product", item);
        }
        return rows;
    }
}

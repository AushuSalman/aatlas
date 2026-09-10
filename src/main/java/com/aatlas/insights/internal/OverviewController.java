package com.aatlas.insights.internal;

import com.aatlas.common.web.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /overview} and its three sub-views: what changed since yesterday, the ranked
 * opportunity list, and the risk radar. Every response is one call to
 * {@link OverviewEngine#compute()} - Overview never has a number of its own, so neither
 * does this controller.
 *
 * <p>Opportunities are returned unordered-by-persona - ranked by money, as the wave-2
 * brief specifies - and the frontend re-orders them client-side using the seat's persona
 * already available from {@code /me}.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/overview", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Overview", description = "The home screen: what changed, where the money is, what could go wrong.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "No or invalid bearer token."),
    @ApiResponse(responseCode = "404", description = "no_catalogue: the tenant has not connected a data source.")
})
class OverviewController {

    private final OverviewEngine engine;

    OverviewController(OverviewEngine engine) {
        this.engine = engine;
    }

    @Operation(summary = "The full Overview payload",
            description = "Since-yesterday sentence data, six KPIs, opportunities (by money), risks, the what-changed "
                    + "feed, recent decisions, every region and branch, and network adoption.")
    @GetMapping
    OverviewEngine.Overview overview() {
        return engine.compute();
    }

    @Operation(summary = "The what-changed feed", description = "Paged; typically under ten rows a day in this demo.")
    @GetMapping("/changes")
    CursorPage<OverviewEngine.ChangeEvent> changes(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String cursor) {
        List<OverviewEngine.ChangeEvent> all = engine.compute().changes();
        return page(all, limit, cursor, OverviewEngine.ChangeEvent::id);
    }

    @Operation(summary = "The opportunity list", description = "Ranked by money; the frontend re-orders per seat.")
    @GetMapping("/opportunities")
    List<OverviewEngine.Opportunity> opportunities() {
        return engine.compute().opportunities();
    }

    @Operation(summary = "The risk radar")
    @GetMapping("/risks")
    List<OverviewEngine.RiskItem> risks() {
        return engine.compute().risks();
    }

    private static <T> CursorPage<T> page(
            List<T> all, int limit, String cursor, java.util.function.Function<T, String> idOf) {
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (idOf.apply(all.get(i)).equals(cursor)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<T> rest = start >= all.size() ? List.of() : all.subList(start, all.size());
        return CursorPage.of(rest, limit, idOf);
    }
}

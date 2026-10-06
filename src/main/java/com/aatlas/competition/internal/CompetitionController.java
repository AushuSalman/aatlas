package com.aatlas.competition.internal;

import com.aatlas.competition.internal.CompetitionDtos.ItemCompetition;
import com.aatlas.competition.internal.CompetitionDtos.LookupResult;
import com.aatlas.competition.internal.CompetitionDtos.ProvidersView;
import com.aatlas.competition.internal.CompetitionDtos.RefreshRequest;
import com.aatlas.competition.internal.CompetitionDtos.RefreshResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live competitor prices and the pricing method. Thin by rule: every call is one
 * {@link CompetitionService} method.
 */
@RestController
@RequestMapping(path = "/api/v1/competition", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Competition", description = "Live competitor prices from shopping-data providers, and how prices are set.")
class CompetitionController {

    private final CompetitionService service;
    private final PriceSourcesService sources;
    private final PriceSchedulesService schedules;

    CompetitionController(CompetitionService service, PriceSourcesService sources, PriceSchedulesService schedules) {
        this.service = service;
        this.sources = sources;
        this.schedules = schedules;
    }

    @Operation(summary = "Which price sources this tenant has switched on, and the latest background price check")
    @GetMapping("/settings")
    CompetitionDtos.SourcesSettings settings() {
        return sources.get();
    }

    @Operation(summary = "Switch price sources on or off",
            description = "Fetches nothing: price-check schedules decide when competitor prices are checked.")
    @org.springframework.web.bind.annotation.PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.SourcesSettings saveSettings(@RequestBody CompetitionDtos.SaveSourcesRequest request) {
        return sources.save(request);
    }

    @Operation(summary = "Price-check schedules: which products, which sources, when, and where each stands")
    @GetMapping("/schedules")
    CompetitionDtos.SchedulesView schedules() {
        return schedules.list();
    }

    @Operation(summary = "Create a price-check schedule",
            description = "All products, some categories or picked items; once or every N hours (6+), days, weeks or "
                    + "months from startsAt (null = now). One that is due at once starts straight away.")
    @PostMapping(path = "/schedules", consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.ScheduleView createSchedule(@RequestBody CompetitionDtos.ScheduleRequest request) {
        return schedules.create(request);
    }

    @Operation(summary = "Change a price-check schedule, or pause or resume it (active)")
    @org.springframework.web.bind.annotation.PutMapping(path = "/schedules/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.ScheduleView updateSchedule(@PathVariable java.util.UUID id,
            @RequestBody CompetitionDtos.ScheduleRequest request) {
        return schedules.update(id, request);
    }

    @Operation(summary = "Delete a price-check schedule; its past runs and the prices they found stay")
    @org.springframework.web.bind.annotation.DeleteMapping("/schedules/{id}")
    org.springframework.http.ResponseEntity<Void> deleteSchedule(@PathVariable java.util.UUID id) {
        schedules.delete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @Operation(summary = "Run a price-check schedule now; its next scheduled run is unchanged")
    @PostMapping("/schedules/{id}/run")
    CompetitionDtos.RunNowResult runSchedule(@PathVariable java.util.UUID id) {
        return schedules.runNow(id);
    }

    @Operation(summary = "The most recent background price check, or nothing")
    @GetMapping("/jobs/latest")
    org.springframework.http.ResponseEntity<CompetitionDtos.JobView> latestJob() {
        CompetitionDtos.JobView job = sources.latestJob();
        return job == null ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(job);
    }

    @Operation(summary = "One background price check's progress")
    @GetMapping("/jobs/{id}")
    CompetitionDtos.JobView job(@PathVariable java.util.UUID id) {
        return sources.job(id);
    }

    @Operation(summary = "Which competitor-price providers are configured, what each covers and costs")
    @GetMapping("/providers")
    ProvidersView providers() {
        return service.providers();
    }

    @Operation(summary = "How a price is set: sources, steps, weights and this tenant's guardrails")
    @GetMapping("/method")
    PricingMethod.MethodView method() {
        return service.method();
    }

    @Operation(summary = "One item's competitor observations on file, each with its source, and the anchor")
    @GetMapping("/items/{item}")
    ItemCompetition item(@PathVariable String item) {
        return service.item(item);
    }

    @Operation(summary = "Search one item's competitor prices now",
            description = "Every listing each provider returned, kept or dropped with the reason. With save=true "
                    + "(the default) the kept listings become today's observations and the item's anchor.")
    @PostMapping("/items/{item}/lookup")
    LookupResult lookup(@PathVariable String item,
            @RequestParam(required = false) List<String> providers,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "true") boolean save) {
        return service.lookup(item, providers, q, save);
    }

    @Operation(summary = "Buy-side benchmarks for one item: retail ceiling and bulk-lot price per unit",
            description = "Retail listings on every configured provider, and lots/cases (eBay by preference) divided "
                    + "down to a unit price. The bulk per-unit price is kept as the item's buying benchmark.")
    @PostMapping("/items/{item}/buy-check")
    CompetitionDtos.BuyCheck buyCheck(@PathVariable String item,
            @RequestParam(required = false) List<String> providers,
            @RequestParam(required = false) String q) {
        return service.buyCheck(item, providers, q);
    }

    @Operation(summary = "The item's last Retail and bulk check, as it was shown",
            description = "Every listing kept or dropped, with checkedAt - when the listings were fetched. 204 when none was run.")
    @GetMapping("/items/{item}/buy-check/latest")
    org.springframework.http.ResponseEntity<CompetitionDtos.BuyCheck> latestBuyCheck(@PathVariable String item) {
        return service.latestBuyCheck(item).map(org.springframework.http.ResponseEntity::ok)
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @Operation(summary = "The competitors this tenant tracks; active ones are priced from their own sites")
    @GetMapping("/competitors")
    List<CompetitionDtos.CompetitorView> competitors() {
        return service.competitors();
    }

    @Operation(summary = "Find online competitors with DataForSEO",
            description = "From your own website's domain (the sites sharing your Google keywords), or from keywords, "
                    + "or item descriptions (the sites ranking for them). Nothing is tracked until you add one.")
    @PostMapping(path = "/competitors/discover", consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.Discovery discover(@RequestBody CompetitionDtos.DiscoverRequest request) {
        return service.discover(request);
    }

    @Operation(summary = "Track a competitor by domain (or re-activate it)")
    @PostMapping(path = "/competitors", consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.CompetitorView track(@Valid @RequestBody CompetitionDtos.TrackRequest request) {
        return service.track(request);
    }

    @Operation(summary = "Pause or resume pricing a tracked competitor")
    @org.springframework.web.bind.annotation.PatchMapping(path = "/competitors/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    CompetitionDtos.CompetitorView setActive(@PathVariable java.util.UUID id,
            @RequestBody CompetitionDtos.ActiveRequest request) {
        return service.setActive(id, request.active());
    }

    @Operation(summary = "Stop tracking a competitor (its saved prices stay)")
    @org.springframework.web.bind.annotation.DeleteMapping("/competitors/{id}")
    org.springframework.http.ResponseEntity<Void> untrack(@PathVariable java.util.UUID id) {
        service.untrack(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @Operation(summary = "Search and save competitor prices for a list of items",
            description = "At most " + CompetitionService.MAX_BULK_ITEMS + " items per call; each item costs one "
                    + "search per provider.")
    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    RefreshResult refresh(@Valid @RequestBody RefreshRequest request) {
        return service.refresh(request.items(), request.providers());
    }
}

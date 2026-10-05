package com.aatlas.competition.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Wire shapes for {@code /api/v1/competition}. */
final class CompetitionDtos {

    private CompetitionDtos() {
    }

    /** Which live sources this environment can search, and what each covers and costs. */
    /**
     * @param discoveryAvailable DataForSEO is configured, so competitors can be found
     * @param trackedActive      competitors whose own sites are priced on a lookup
     */
    record ProvidersView(boolean anyAvailable, int maxBulkItems, List<ProviderView> providers,
            boolean discoveryAvailable, int trackedActive) {
    }

    // ---- Settings → Competitor price sources, and the background jobs ----------------------

    /**
     * @param configured  the tenant has chosen at least once (the post-import prompt shows until then)
     * @param productCount products in the catalogue - what a full price check covers
     * @param latestJob    the most recent background price check, or null
     */
    record SourcesSettings(boolean configured, List<SourceView> sources, int productCount, JobView latestJob,
            boolean dailyRefresh, String dailyAt) {
    }

    /**
     * @param available  the server has this source's key
     * @param enabled    the tenant has switched it on
     * @param comingSoon shown, but not selectable yet
     */
    record SourceView(String key, String label, String description, boolean available, boolean enabled,
            boolean comingSoon) {
    }

    /**
     * @param fetchNow     start a price check over the whole catalogue; null = only the first time
     * @param dailyRefresh re-check every product once a day; null keeps the current setting
     */
    record SaveSourcesRequest(List<String> enabledSources, Boolean fetchNow, Boolean dailyRefresh) {
    }

    /** @param job the price check the save started, or null */
    record SaveSourcesResult(SourcesSettings settings, JobView job) {
    }

    /**
     * A background price check.
     *
     * @param trigger {@code setup} (from Settings), {@code import} (new products) or {@code manual}
     * @param status  {@code queued}, {@code running}, {@code done} or {@code failed}
     * @param sources the sources' labels
     * @param priced  items with at least one competitor price kept
     */
    record JobView(java.util.UUID id, String trigger, String status, List<String> sources, int total, int done,
            int priced, int observations, int failed, String error, java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime finishedAt) {
    }

    /** A competitor the tenant follows; its own product pages are priced on every lookup while active. */
    record CompetitorView(java.util.UUID id, String domain, String name, String source, boolean active) {
    }

    /** @param source {@code dataforseo} when picked from a discovery, else {@code manual} */
    record TrackRequest(@jakarta.validation.constraints.NotBlank String domain, String name, String source,
            Map<String, Object> discovery) {
    }

    record ActiveRequest(boolean active) {
    }

    /**
     * What to discover competitors from - one of: the tenant's own website ({@code domain}),
     * explicit {@code keywords}, or {@code items} (their descriptions). None: the selling catalogue.
     */
    record DiscoverRequest(String domain, List<String> keywords, List<String> items, Integer limit) {
    }

    /**
     * @param mode         {@code domain} or {@code keywords}
     * @param keywordCount how many keywords were searched
     * @param cost         what DataForSEO charged for the call, in USD
     */
    record Discovery(String mode, String domain, int keywordCount, List<String> sampleKeywords, BigDecimal cost,
            List<Candidate> candidates) {
    }

    /**
     * A competing domain.
     *
     * @param kind          {@code store}, {@code marketplace} (the shopping providers already cover it) or
     *                      {@code not-a-store} (information sites, social)
     * @param keywordsCount keywords it ranks for among those searched (shared keywords, for a domain search)
     * @param etv           estimated monthly organic visits from those keywords
     * @param topKeywords   its best positions, "pvc ball valve (#3)"
     */
    record Candidate(String domain, String name, String kind, BigDecimal avgPosition, Integer keywordsCount,
            BigDecimal etv, List<String> topKeywords, boolean tracked) {
    }

    record ProviderView(String key, String label, String plan, boolean available, String envVars) {
    }

    /**
     * One item's live lookup: every listing each provider returned, what was kept and why the
     * rest were not, and - when saved - how the item's competitor anchor moved.
     */
    record LookupResult(String item, String description, String query, String currency, List<ProviderRun> providers,
            Summary summary, boolean saved, int observationsSaved, ItemCompetition stored) {
    }

    /** @param status {@code ok}, {@code failed} or {@code unavailable} */
    record ProviderRun(String key, String label, String status, String message, List<ListingView> listings) {
    }

    /**
     * @param price       per unit - for a bulk listing, the listing's price divided by its quantity
     * @param quantity    units in the listing, for a bulk lot; null for a single-unit listing
     * @param listingPrice the listing's own price, for a bulk lot; null otherwise
     */
    record ListingView(String title, BigDecimal price, String currency, String merchant, String url, boolean kept,
            String reason, BigDecimal matchPct, Integer quantity, BigDecimal listingPrice) {
    }

    /**
     * The buy side's check: what the item sells for at retail (the ceiling a supplier's price
     * should sit well under) and what it goes for per unit in bulk lots (a rough trade price).
     * Retail is not saved; the bulk per-unit price is kept as the buying benchmark the buy recommendation reads.
     * {@code checkedAt} is when the listings were fetched - they are live, so the page says how old they are.
     */
    record BuyCheck(String item, String description, String currency, Side retail, Side bulk,
            java.time.Instant checkedAt) {
    }

    /** @param note why a side was not searched, or what it rests on */
    record Side(String query, Summary summary, List<ProviderRun> providers, String note) {
    }

    record Summary(int found, int kept, BigDecimal median, BigDecimal low, BigDecimal high) {
    }

    /**
     * What the pricing engines read for this item today: the anchor (the median the
     * recommendation starts from) and the observations behind it, each with its source.
     */
    record ItemCompetition(String item, String description, BigDecimal anchor, int observations,
            List<Observation> rows, Map<String, Integer> bySource) {
    }

    record Observation(String competitor, BigDecimal price, String currency, LocalDate observedAt, String regionKey,
            String source, String sourceLabel, String url) {
    }

    record RefreshRequest(@NotEmpty @Size(max = CompetitionService.MAX_BULK_ITEMS) List<String> items,
            List<String> providers) {
    }

    record RefreshResult(int items, int searched, int observationsSaved, List<RefreshRow> rows) {
    }

    /** @param status {@code saved}, {@code nothing-kept}, {@code failed} or {@code not-found} */
    record RefreshRow(String item, String description, String status, int found, int kept, BigDecimal median,
            BigDecimal anchor, String message) {
    }
}

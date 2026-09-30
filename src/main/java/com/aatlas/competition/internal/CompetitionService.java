package com.aatlas.competition.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.competition.internal.CompetitionDtos.ItemCompetition;
import com.aatlas.competition.internal.CompetitionDtos.ListingView;
import com.aatlas.competition.internal.CompetitionDtos.LookupResult;
import com.aatlas.competition.internal.CompetitionDtos.Observation;
import com.aatlas.competition.internal.CompetitionDtos.ProviderRun;
import com.aatlas.competition.internal.CompetitionDtos.ProviderView;
import com.aatlas.competition.internal.CompetitionDtos.ProvidersView;
import com.aatlas.competition.internal.CompetitionDtos.RefreshResult;
import com.aatlas.competition.internal.CompetitionDtos.RefreshRow;
import com.aatlas.competition.internal.CompetitionDtos.Summary;
import com.aatlas.competition.internal.ListingFilter.Judged;
import com.aatlas.competition.internal.ShoppingProvider.Listing;
import com.aatlas.competition.internal.ShoppingProvider.Market;
import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live competitor prices: search, judge, save, and show what the engines now read.
 *
 * <p>An item is searched on every available provider at once (or the ones asked for); all the
 * listings are judged together by {@link ListingFilter}, so the same store found by two
 * providers counts once and the outlier median is over everything found. What is kept is
 * written through {@link CompetitorObservations}; the item's anchor after the write is the
 * figure the sell recommendation, the bulk plan and the price wizard start from.
 */
@Service
class CompetitionService {

    private static final Logger log = LoggerFactory.getLogger(CompetitionService.class);

    /** Items per bulk call: each costs a search per provider, and providers meter searches. */
    static final int MAX_BULK_ITEMS = 25;
    static final int LISTINGS_PER_PROVIDER = 20;

    private final List<ShoppingProvider> providers;
    private final Catalogue catalogue;
    private final CompetitorPrices competitorPrices;
    private final CompetitorObservations observations;
    private final PricingMethod method;
    private final TrackedCompetitors tracked;
    private final DataForSeoClient dataForSeo;
    private final PriceSourceSettings sourceSettings;
    private final AatlasClock clock;

    CompetitionService(List<ShoppingProvider> providers, Catalogue catalogue, CompetitorPrices competitorPrices,
            CompetitorObservations observations, PricingMethod method, TrackedCompetitors tracked,
            DataForSeoClient dataForSeo, PriceSourceSettings sourceSettings, AatlasClock clock) {
        // Priority order: when two providers find the same store, the first one's listing is kept.
        // A competitor's own page outranks an aggregator's listing of the same store.
        List<String> order = List.of(CompetitorSites.KEY, "serpapi", "oxylabs", "rainforest", "ebay");
        this.providers = providers.stream()
                .sorted(java.util.Comparator.comparingInt(p -> order.indexOf(p.key()) < 0 ? 99 : order.indexOf(p.key())))
                .toList();
        this.catalogue = catalogue;
        this.competitorPrices = competitorPrices;
        this.observations = observations;
        this.method = method;
        this.tracked = tracked;
        this.dataForSeo = dataForSeo;
        this.sourceSettings = sourceSettings;
        this.clock = clock;
    }

    PricingMethod.MethodView method() {
        return method.describe(providers);
    }

    ProvidersView providers() {
        List<ProviderView> views = providers.stream()
                .map(p -> new ProviderView(p.key(), p.label(), p.plan(), p.available(), envVars(p.key())))
                .toList();
        int active = tracked.active(TenantContext.requireTenantId()).size();
        return new ProvidersView(views.stream().anyMatch(ProviderView::available), MAX_BULK_ITEMS, views,
                dataForSeo.available(), active);
    }

    // ---- tracked competitors -------------------------------------------------------------

    List<CompetitionDtos.CompetitorView> competitors() {
        return tracked.list(TenantContext.requireTenantId()).stream().map(CompetitionService::view).toList();
    }

    CompetitionDtos.CompetitorView track(CompetitionDtos.TrackRequest req) {
        String source = "dataforseo".equals(req.source()) ? "dataforseo" : "manual";
        try {
            return view(tracked.upsert(TenantContext.requireTenantId(), req.domain(), req.name(), source,
                    req.discovery()));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_domain", ex.getMessage());
        }
    }

    CompetitionDtos.CompetitorView setActive(UUID id, boolean active) {
        UUID tenant = TenantContext.requireTenantId();
        if (!tracked.setActive(tenant, id, active)) {
            throw ApiException.notFound("Competitor", id);
        }
        return tracked.list(tenant).stream().filter(t -> t.id().equals(id)).map(CompetitionService::view)
                .findFirst().orElseThrow();
    }

    void untrack(UUID id) {
        if (!tracked.delete(TenantContext.requireTenantId(), id)) {
            throw ApiException.notFound("Competitor", id);
        }
    }

    /**
     * Who competes online, from DataForSEO: with {@code ownDomain}, the domains sharing the most
     * Google keywords with the tenant's site; otherwise the domains ranking for the keywords given,
     * or for the descriptions of the items given, or of the tenant's selling items (up to
     * {@link #MAX_DISCOVERY_KEYWORDS}). Nothing is tracked until a person picks.
     */
    CompetitionDtos.Discovery discover(CompetitionDtos.DiscoverRequest req) {
        if (!dataForSeo.available()) {
            throw ApiException.badRequest("discovery_unavailable",
                    "Competitor discovery needs DataForSEO: set DATAFORSEO_LOGIN and DATAFORSEO_PASSWORD.");
        }
        int location = Market.of(catalogue.country()).uk() ? 2826 : 2840;
        int limit = req.limit() == null ? 30 : Math.max(5, Math.min(req.limit(), 100));
        String own = null;
        if (req.domain() != null && !req.domain().isBlank()) {
            try {
                own = TrackedCompetitors.normalise(req.domain());
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("invalid_domain", ex.getMessage());
            }
        }
        List<String> keywords = own != null ? List.of() : discoveryKeywords(req);
        if (own == null && keywords.isEmpty()) {
            throw ApiException.badRequest("no_keywords", "Give keywords, items, or your own website's domain.");
        }
        DataForSeoClient.Found found;
        try {
            found = own != null ? dataForSeo.domainCompetitors(own, location, limit)
                    : dataForSeo.serpCompetitors(keywords, location, limit);
        } catch (ShoppingProvider.ProviderFailed ex) {
            throw ApiException.badRequest("discovery_failed", ex.getMessage());
        }
        java.util.Set<String> already = new java.util.HashSet<>();
        tracked.list(TenantContext.requireTenantId()).forEach(t -> already.add(t.domain()));
        String self = own;
        List<CompetitionDtos.Candidate> candidates = found.competitors().stream()
                .filter(c -> c.domain() != null && !c.domain().isBlank())
                .map(c -> c.domain().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""))
                .distinct()
                .filter(d -> !d.equals(self))
                .map(d -> {
                    DataForSeoClient.Competitor c = found.competitors().stream()
                            .filter(x -> x.domain().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").equals(d))
                            .findFirst().orElseThrow();
                    List<String> top = c.keywordPositions().entrySet().stream()
                            .sorted(java.util.Comparator.comparingInt(e -> e.getValue().isEmpty() ? 999 : e.getValue().get(0)))
                            .limit(3).map(e -> e.getKey() + " (#" + (e.getValue().isEmpty() ? "?" : e.getValue().get(0)) + ")")
                            .toList();
                    return new CompetitionDtos.Candidate(d, nameOf(d), kindOf(d), c.avgPosition(), c.keywordsCount(),
                            c.etv(), top, already.contains(d));
                })
                .toList();
        return new CompetitionDtos.Discovery(own != null ? "domain" : "keywords", own, keywords.size(),
                keywords.stream().limit(10).toList(), found.cost(), candidates);
    }

    static final int MAX_DISCOVERY_KEYWORDS = 50;

    private List<String> discoveryKeywords(CompetitionDtos.DiscoverRequest req) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (req.keywords() != null) {
            req.keywords().stream().filter(k -> k != null && !k.isBlank())
                    .forEach(k -> out.add(k.strip().toLowerCase(Locale.ROOT)));
        }
        if (req.items() != null) {
            for (String item : req.items()) {
                catalogue.product(item).ifPresent(p -> out.add(defaultQuery(p).toLowerCase(Locale.ROOT)));
            }
        }
        if (out.isEmpty()) {
            catalogue.products().stream().filter(Catalogue.ProductRef::hasSales)
                    .map(p -> defaultQuery(p).toLowerCase(Locale.ROOT))
                    .forEach(out::add);
        }
        return out.stream().limit(MAX_DISCOVERY_KEYWORDS).toList();
    }

    private static final java.util.Set<String> MARKETPLACES = java.util.Set.of("amazon.com", "amazon.co.uk",
            "ebay.com", "ebay.co.uk", "walmart.com", "etsy.com", "alibaba.com", "aliexpress.com", "temu.com",
            "target.com", "wayfair.com");
    private static final java.util.Set<String> NOT_STORES = java.util.Set.of("wikipedia.org", "youtube.com",
            "reddit.com", "quora.com", "pinterest.com", "facebook.com", "instagram.com", "linkedin.com", "tiktok.com",
            "x.com", "twitter.com", "google.com", "bing.com", "yelp.com", "bbb.org", "thisoldhouse.com", "hunker.com",
            "familyhandyman.com", "bobvila.com", "homeadvisor.com", "angi.com", "forbes.com", "nytimes.com");

    /** {@code store}, {@code marketplace} (already covered by the shopping providers) or {@code not-a-store}. */
    static String kindOf(String domain) {
        if (MARKETPLACES.contains(domain)) {
            return "marketplace";
        }
        if (NOT_STORES.contains(domain) || domain.endsWith(".gov") || domain.endsWith(".edu")
                || domain.endsWith("wikipedia.org")) {
            return "not-a-store";
        }
        return "store";
    }

    /** "homedepot.com" → "Homedepot"; a person can rename it when tracking. */
    static String nameOf(String domain) {
        String base = domain.split("\\.")[0];
        return base.isEmpty() ? domain : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private static CompetitionDtos.CompetitorView view(TrackedCompetitors.Tracked t) {
        return new CompetitionDtos.CompetitorView(t.id(), t.domain(), t.name(), t.source(), t.active());
    }

    ItemCompetition item(String itemNumber) {
        Catalogue.ProductRef product = product(itemNumber);
        return stored(product, clock.today());
    }

    /**
     * Search one item now.
     *
     * @param query what to search for; blank means the item's description
     * @param save  write the kept listings as today's observations
     */
    LookupResult lookup(String itemNumber, List<String> providerKeys, String query, boolean save) {
        Catalogue.ProductRef product = product(itemNumber);
        List<ShoppingProvider> chosen = chosen(providerKeys);
        Market market = Market.of(catalogue.country());
        String q = query == null || query.isBlank() ? defaultQuery(product) : query.strip();

        Searched searched = search(chosen, q, market, sites(chosen));
        List<Judged> judged = ListingFilter.judge(q, market.currency(), searched.listings());
        List<Listing> kept = judged.stream().filter(Judged::kept).map(Judged::listing).toList();

        LocalDate today = clock.today();
        int written = save ? observations.save(TenantContext.requireTenantId(), product.id(), today, kept) : 0;

        return new LookupResult(product.itemNumber(), product.description(), q, market.currency(),
                runs(chosen, searched, judged, Map.of()), summary(searched.listings().size(), kept), save, written,
                stored(product, today));
    }

    /**
     * The buy side's benchmarks for one item: retail listings on every chosen provider (the
     * ceiling), and bulk lots - "lot of 50", "case of 25" - read down to a per-unit price (a rough
     * trade price). Bulk is searched on eBay, where trade sellers list lots, or on the first
     * available provider when eBay is not configured. The bulk result is kept as the item's buying
     * benchmark ({@code buy_market_benchmarks}); retail listings are not written.
     */
    CompetitionDtos.BuyCheck buyCheck(String itemNumber, List<String> providerKeys, String query) {
        Catalogue.ProductRef product = product(itemNumber);
        // Competitor sites are sell-side prices at two scraper requests each - not a buy benchmark.
        List<ShoppingProvider> chosen = chosen(providerKeys).stream()
                .filter(p -> !CompetitorSites.KEY.equals(p.key())).toList();
        Market market = Market.of(catalogue.country());
        String q = query == null || query.isBlank() ? defaultQuery(product) : query.strip();

        Searched retail = search(chosen, q, market, List.of());
        List<Judged> retailJudged = ListingFilter.judge(q, market.currency(), retail.listings());
        List<Listing> retailKept = retailJudged.stream().filter(Judged::kept).map(Judged::listing).toList();
        CompetitionDtos.Side retailSide = new CompetitionDtos.Side(q,
                summary(retail.listings().size(), retailKept), runs(chosen, retail, retailJudged, Map.of()),
                "Shelf prices anyone can buy at. A supplier's landed cost should sit well under the low end.");

        List<ShoppingProvider> bulkOn = providers.stream().filter(p -> p.key().equals("ebay") && p.available()).toList();
        if (bulkOn.isEmpty()) {
            bulkOn = chosen.stream().filter(ShoppingProvider::available).limit(1).toList();
        }
        String bulkQuery = q + " bulk lot case";
        Searched bulk = search(bulkOn, bulkQuery, market, List.of());
        java.util.IdentityHashMap<Listing, Listing> perUnitToLot = new java.util.IdentityHashMap<>();
        List<Judged> bulkJudged = judgeBulk(q, market.currency(), bulk.listings(), perUnitToLot);
        List<Listing> bulkKept = bulkJudged.stream().filter(Judged::kept).map(Judged::listing).toList();
        // Kept as a buying benchmark: the buy recommendation counts it as market evidence.
        observations.saveBulkBenchmark(TenantContext.requireTenantId(), product.id(), clock.today(), bulkKept,
                market.currency());
        CompetitionDtos.Side bulkSide = new CompetitionDtos.Side(bulkQuery, summary(bulk.listings().size(), bulkKept),
                runs(bulkOn, bulk, bulkJudged, perUnitToLot),
                "Per-unit prices of lots and cases (listing price ÷ quantity in the title). A rough trade price - "
                        + "real distributor prices are quoted privately; ask your panel with an RFQ.");

        return new CompetitionDtos.BuyCheck(product.itemNumber(), product.description(), market.currency(), retailSide,
                bulkSide);
    }

    /**
     * Bulk listings judged per unit: a title with no readable quantity is dropped first; the rest
     * are divided down to a unit price and go through the same rules as a retail listing.
     */
    static List<Judged> judgeBulk(String query, String currency, List<Listing> listings,
            java.util.IdentityHashMap<Listing, Listing> perUnitToLot) {
        List<Listing> perUnit = new ArrayList<>();
        for (Listing l : listings) {
            Integer qty = LotQuantity.parse(l.title());
            if (qty != null && l.price() != null) {
                Listing unit = new Listing(l.provider(), l.title(),
                        l.price().divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP), l.currency(), l.merchant(),
                        l.url());
                perUnitToLot.put(unit, l);
                perUnit.add(unit);
            }
        }
        java.util.Iterator<Judged> judged = ListingFilter.judge(query, currency, perUnit, false).iterator();
        List<Judged> out = new ArrayList<>();
        for (Listing l : listings) {
            if (LotQuantity.parse(l.title()) != null && l.price() != null) {
                out.add(judged.next());
            } else {
                out.add(new Judged(l, false, "No pack or lot size in the title - not a bulk price",
                        ListingFilter.match(ListingFilter.words(query), l.title())));
            }
        }
        return out;
    }

    private List<ProviderRun> runs(List<ShoppingProvider> chosen, Searched searched, List<Judged> judged,
            Map<Listing, Listing> perUnitToLot) {
        List<ProviderRun> runs = new ArrayList<>();
        for (ShoppingProvider p : chosen) {
            String failure = searched.failures().get(p.key());
            List<ListingView> views = judged.stream()
                    .filter(j -> j.listing().provider().equals(p.key()))
                    .map(j -> view(j, perUnitToLot.get(j.listing())))
                    .toList();
            String status = !p.available() ? "unavailable" : failure != null ? "failed" : "ok";
            String note = searched.notes().get(p.key());
            String message = !p.available() ? "Not configured: set " + envVars(p.key()) + "."
                    : failure != null ? failure
                    : note != null ? note
                    : views.isEmpty() ? "No priced listings for this search." : null;
            runs.add(new ProviderRun(p.key(), p.label(), status, message, views));
        }
        return runs;
    }

    /** Search and save a list of items, one after another; one item's failure never stops the rest. */
    RefreshResult refresh(List<String> items, List<String> providerKeys) {
        if (items.size() > MAX_BULK_ITEMS) {
            throw ApiException.badRequest("too_many_items",
                    "At most " + MAX_BULK_ITEMS + " items per refresh; send the rest in another call.");
        }
        List<ShoppingProvider> chosen = chosen(providerKeys);
        List<TrackedCompetitors.Tracked> sites = sites(chosen);
        List<RefreshRow> rows = new ArrayList<>();
        int saved = 0;
        int searched = 0;
        for (String itemNumber : items.stream().distinct().toList()) {
            RefreshRow row = refreshItem(itemNumber, chosen, sites);
            if (!"not-found".equals(row.status())) {
                searched++;
            }
            saved += row.kept();
            rows.add(row);
        }
        log.info("Competitor refresh: {} items searched on {}, {} observations saved", searched,
                chosen.stream().map(ShoppingProvider::key).toList(), saved);
        return new RefreshResult(items.size(), searched, saved, rows);
    }

    /**
     * Search one item on {@code chosen} and save what is kept - one step of a bulk refresh or a
     * background job. Needs a bound tenant. {@code kept} on the row is also the observations written.
     */
    RefreshRow refreshItem(String itemNumber, List<ShoppingProvider> chosen, List<TrackedCompetitors.Tracked> sites) {
        var product = catalogue.product(itemNumber).orElse(null);
        if (product == null) {
            return new RefreshRow(itemNumber, null, "not-found", 0, 0, null, null, "Not in the catalogue.");
        }
        Market market = Market.of(catalogue.country());
        LocalDate today = clock.today();
        String q = defaultQuery(product);
        Searched s = search(chosen, q, market, sites);
        List<Listing> kept = ListingFilter.judge(q, market.currency(), s.listings()).stream()
                .filter(Judged::kept).map(Judged::listing).toList();
        observations.save(TenantContext.requireTenantId(), product.id(), today, kept);
        Summary sum = summary(s.listings().size(), kept);
        BigDecimal anchor = competitorPrices.anchor(product.id(), null, null, today).map(Anchor::value).orElse(null);
        long searchedOn = chosen.stream().filter(ShoppingProvider::available).count();
        String status = !kept.isEmpty() ? "saved"
                : searchedOn > 0 && s.failures().size() >= searchedOn ? "failed" : "nothing-kept";
        String message = s.failures().isEmpty() ? null : String.join("; ", s.failures().values());
        return new RefreshRow(product.itemNumber(), product.description(), status, sum.found(), sum.kept(),
                sum.median(), anchor, message);
    }

    /**
     * The buying benchmark for one item, refreshed without a person: eBay bulk lots, divided down to
     * a unit price and saved as {@code buy_market_benchmarks} - what the "Retail and bulk check" does,
     * run by the daily job. eBay only (free, 5,000 searches a day); nothing when it is not set up.
     * Needs a bound tenant.
     *
     * @return whether a bulk price was found and saved
     */
    boolean refreshBulkBenchmark(String itemNumber) {
        var product = catalogue.product(itemNumber).orElse(null);
        ShoppingProvider ebay = providers.stream().filter(p -> "ebay".equals(p.key()) && p.available())
                .findFirst().orElse(null);
        if (product == null || ebay == null) {
            return false;
        }
        Market market = Market.of(catalogue.country());
        String q = defaultQuery(product);
        Searched bulk = search(List.of(ebay), q + " bulk lot case", market, List.of());
        List<Listing> kept = judgeBulk(q, market.currency(), bulk.listings(), new java.util.IdentityHashMap<>())
                .stream().filter(Judged::kept).map(Judged::listing).toList();
        observations.saveBulkBenchmark(TenantContext.requireTenantId(), product.id(), clock.today(), kept,
                market.currency());
        return !kept.isEmpty();
    }

    /** The configured providers among {@code keys}, in priority order; unknown or keyless keys are skipped. */
    List<ShoppingProvider> providersFor(List<String> keys) {
        return providers.stream().filter(p -> keys.contains(p.key()) && p.available()).toList();
    }

    /** Every provider, configured or not, in priority order - for the settings screen. */
    List<ShoppingProvider> allProviders() {
        return providers;
    }

    // ---- internals ---------------------------------------------------------------------

    /**
     * @param failures provider → why it returned nothing at all
     * @param notes    provider → what went partly wrong (a competitor site with no page or no price)
     */
    private record Searched(List<Listing> listings, Map<String, String> failures, Map<String, String> notes) {
    }

    /** The tracked competitors to price, read on the request thread (it carries the tenant); none unless sites were chosen. */
    private List<TrackedCompetitors.Tracked> sites(List<ShoppingProvider> chosen) {
        boolean wanted = chosen.stream().anyMatch(p -> CompetitorSites.KEY.equals(p.key()) && p.available());
        return wanted ? tracked.active(TenantContext.requireTenantId()) : List.of();
    }

    /** Every chosen provider at once, on virtual threads; results kept in provider priority order. */
    private Searched search(List<ShoppingProvider> chosen, String query, Market market,
            List<TrackedCompetitors.Tracked> sites) {
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        List<Listing> all = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<ShoppingProvider, Future<List<Listing>>> futures = new LinkedHashMap<>();
            for (ShoppingProvider p : chosen) {
                if (!p.available()) {
                    continue;
                }
                if (p instanceof CompetitorSites cs) {
                    if (sites.isEmpty()) {
                        notes.put(p.key(), "No competitors tracked yet - find them under Competitors.");
                        continue;
                    }
                    futures.put(p, pool.submit(() -> {
                        List<Listing> found = new ArrayList<>();
                        List<String> problems = new ArrayList<>();
                        for (CompetitorSites.SiteResult r : cs.searchSites(query, market, sites)) {
                            found.addAll(r.listings());
                            if (r.problem() != null) {
                                problems.add(r.domain() + ": " + r.problem());
                            }
                        }
                        if (!problems.isEmpty()) {
                            synchronized (notes) {
                                notes.put(p.key(), String.join("; ", problems));
                            }
                        }
                        return found;
                    }));
                } else {
                    futures.put(p, pool.submit(() -> p.search(query, market, LISTINGS_PER_PROVIDER)));
                }
            }
            for (var e : futures.entrySet()) {
                try {
                    all.addAll(e.getValue().get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    log.warn("{} search failed for \"{}\": {}", e.getKey().key(), query, cause.getMessage());
                    failures.put(e.getKey().key(), cause.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failures.put(e.getKey().key(), "interrupted");
                }
            }
        }
        return new Searched(all, failures, notes);
    }

    private List<ShoppingProvider> chosen(List<String> keys) {
        if (providers.stream().noneMatch(ShoppingProvider::available)) {
            throw ApiException.badRequest("competition_unavailable",
                    "No competitor-price provider is configured. Set SERPAPI_API_KEY (Google Shopping, free tier), "
                            + "EBAY_CLIENT_ID and EBAY_CLIENT_SECRET (free), RAINFOREST_API_KEY or OXYLABS_USERNAME "
                            + "and OXYLABS_PASSWORD.");
        }
        if (keys == null || keys.isEmpty()) {
            // The tenant's own choice from Settings, once made; before that, every configured source.
            PriceSourceSettings.Settings s = sourceSettings.get(TenantContext.requireTenantId());
            if (s.configured()) {
                List<ShoppingProvider> on = providersFor(s.enabled());
                if (on.isEmpty()) {
                    throw ApiException.badRequest("no_sources_enabled",
                            "No price source is switched on. Choose them in Settings → Competitor price sources.");
                }
                return on;
            }
            return providers.stream().filter(ShoppingProvider::available)
                    .filter(p -> !CompetitorSites.KEY.equals(p.key())).toList();
        }
        List<String> wanted = keys.stream().map(k -> k.strip().toLowerCase(Locale.ROOT)).toList();
        List<ShoppingProvider> out = providers.stream().filter(p -> wanted.contains(p.key())).toList();
        if (out.isEmpty()) {
            throw ApiException.badRequest("unknown_provider",
                    "Unknown provider " + keys + "; one of " + providers.stream().map(ShoppingProvider::key).toList());
        }
        // Settings is the customer's choice: once made, a lookup never searches a source switched
        // off there, whatever the screen asked for.
        PriceSourceSettings.Settings s = sourceSettings.get(TenantContext.requireTenantId());
        if (s.configured()) {
            List<ShoppingProvider> allowed = out.stream().filter(p -> s.enabled().contains(p.key())).toList();
            if (allowed.isEmpty()) {
                throw ApiException.badRequest("sources_switched_off",
                        "Those price sources are switched off in Settings → Competitor price sources.");
            }
            return allowed;
        }
        return out;
    }

    private Catalogue.ProductRef product(String itemNumber) {
        return catalogue.product(itemNumber).orElseThrow(() -> ApiException.notFound("Item", itemNumber));
    }

    /** The description is what a shopper would type; a bare item number finds nothing. */
    static String defaultQuery(Catalogue.ProductRef p) {
        if (p.description() != null && !p.description().isBlank()) {
            return p.description().strip();
        }
        return p.shortName() != null && !p.shortName().isBlank() ? p.shortName().strip() : p.itemNumber();
    }

    private ItemCompetition stored(Catalogue.ProductRef product, LocalDate today) {
        List<Observation> rows = observations.forItem(TenantContext.requireTenantId(), product.id(), today).stream()
                .map(s -> new Observation(s.competitor(), s.price(), s.currency(), s.observedAt(), s.regionKey(),
                        s.source(), sourceLabel(s.source()), s.url()))
                .toList();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        rows.forEach(r -> bySource.merge(r.sourceLabel(), 1, Integer::sum));
        Anchor anchor = competitorPrices.anchor(product.id(), null, null, today).orElse(null);
        return new ItemCompetition(product.itemNumber(), product.description(),
                anchor == null ? null : anchor.value(), anchor == null ? 0 : anchor.observations(), rows, bySource);
    }

    private String sourceLabel(String source) {
        if (source == null) {
            return "Unknown";
        }
        return switch (source) {
            case "import" -> "Your CSV import";
            case "sample" -> "Sample data";
            case "manual" -> "Entered by hand";
            default -> providers.stream().filter(p -> p.key().equals(source)).map(ShoppingProvider::label)
                    .findFirst().orElse(source);
        };
    }

    static String envVars(String key) {
        return switch (key) {
            case "serpapi" -> "SERPAPI_API_KEY";
            case "ebay" -> "EBAY_CLIENT_ID and EBAY_CLIENT_SECRET";
            case "rainforest" -> "RAINFOREST_API_KEY";
            case "oxylabs", CompetitorSites.KEY -> "OXYLABS_USERNAME and OXYLABS_PASSWORD";
            default -> key;
        };
    }

    /** @param lot the bulk listing a per-unit listing was divided from; null for a retail listing */
    private static ListingView view(Judged j, Listing lot) {
        Listing l = j.listing();
        return new ListingView(l.title(), l.price(), l.currency(), l.merchant(), l.url(), j.kept(), j.reason(),
                j.match().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP),
                lot == null ? null : LotQuantity.parse(lot.title()), lot == null ? null : lot.price());
    }

    private static Summary summary(int found, List<Listing> kept) {
        List<BigDecimal> prices = kept.stream().map(Listing::price).toList();
        return new Summary(found, kept.size(), ListingFilter.median(prices),
                prices.stream().min(BigDecimal::compareTo).orElse(null),
                prices.stream().max(BigDecimal::compareTo).orElse(null));
    }
}

package com.aatlas.competition.internal;

import com.aatlas.competition.internal.TrackedCompetitors.Tracked;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Prices read off the tracked competitors' own product pages, through Oxylabs' Web Scraper API.
 *
 * <p>Per competitor, two realtime requests: a Google search restricted to its domain
 * ({@code site:homedepot.com 1/2 in PVC ball valve}) for the product page, then the page itself
 * ({@code source=universal}), whose price is read by {@link ProductPageParser} from the structured
 * data the store publishes for Google. The competitors are fetched at once; one that fails, has
 * no matching page, or publishes no price is reported and skipped.
 *
 * <p>Not a keyword provider: it needs the tenant's tracked competitors, which the caller reads on
 * the request thread, so {@link #search} is not used - {@link #searchSites} is.
 */
@Component
class CompetitorSites implements ShoppingProvider {

    static final String KEY = "site";

    private final RestClient http;
    private final String username;
    private final String password;

    CompetitorSites(@Value("${aatlas.competition.oxylabs.base-url}") String baseUrl,
            @Value("${aatlas.competition.oxylabs.username}") String username,
            @Value("${aatlas.competition.oxylabs.password}") String password) {
        this.username = username;
        this.password = password;
        // Realtime holds the connection until the job is done; a page fetch can take a while.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Competitor sites (Oxylabs)";
    }

    @Override
    public String plan() {
        return "The price on each tracked competitor's own product page. Two Oxylabs requests per competitor per "
                + "item (about $1 per 1,000 pages); uses the Oxylabs account.";
    }

    @Override
    public boolean available() {
        return !ShoppingProvider.blank(username) && !ShoppingProvider.blank(password);
    }

    @Override
    public List<Listing> search(String query, Market market, int max) {
        throw new UnsupportedOperationException("Competitor sites are searched with searchSites");
    }

    /** What happened at one competitor, for the lookup's report. */
    record SiteResult(String domain, List<Listing> listings, String problem) {
    }

    List<SiteResult> searchSites(String query, Market market, List<Tracked> sites) {
        List<SiteResult> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Tracked, Future<SiteResult>> futures = new LinkedHashMap<>();
            for (Tracked t : sites) {
                futures.put(t, pool.submit(() -> one(query, market, t)));
            }
            for (var e : futures.entrySet()) {
                try {
                    out.add(e.getValue().get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    out.add(new SiteResult(e.getKey().domain(), List.of(), cause.getMessage()));
                }
            }
        }
        return out;
    }

    private SiteResult one(String query, Market market, Tracked t) {
        String page = productPage(query, market, t.domain());
        if (page == null) {
            return new SiteResult(t.domain(), List.of(), "No product page found for this item on " + t.domain());
        }
        String html = fetch(page, market);
        ProductPageParser.Priced priced = ProductPageParser.parse(html);
        if (priced == null) {
            // Listed with no price is still a result: the screen shows the page and why it was not used.
            return new SiteResult(t.domain(), List.of(new Listing(KEY, page, null, null, t.name(), page)),
                    "The page publishes no price in its structured data (it may be drawn by JavaScript)");
        }
        String title = priced.title() != null ? priced.title() : page;
        return new SiteResult(t.domain(), List.of(new Listing(KEY, title, priced.price(),
                priced.currency() != null ? priced.currency() : market.currency(), t.name(), page)), null);
    }

    /** The first organic result on the competitor's own domain for {@code site:domain query}. */
    String productPage(String query, Market market, String domain) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "google_search");
        body.put("query", "site:" + domain + " " + query);
        body.put("geo_location", market.uk() ? "United Kingdom" : "United States");
        body.put("locale", market.uk() ? "en-gb" : "en-us");
        body.put("parse", true);
        JsonNode res = post(body);
        for (JsonNode r : res.path("results").path(0).path("content").path("results").path("organic")) {
            String url = r.path("url").asText(null);
            if (url != null && onDomain(url, domain)) {
                return url;
            }
        }
        return null;
    }

    String fetch(String url, Market market) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "universal");
        body.put("url", url);
        body.put("geo_location", market.uk() ? "United Kingdom" : "United States");
        JsonNode res = post(body);
        JsonNode first = res.path("results").path(0);
        int status = first.path("status_code").asInt(200);
        if (status >= 400) {
            throw new ProviderFailed(domainOf(url) + " answered " + status);
        }
        return first.path("content").isTextual() ? first.path("content").asText() : null;
    }

    private JsonNode post(Map<String, Object> body) {
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        try {
            JsonNode res = http.post()
                    .uri("/v1/queries")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null) {
                throw new ProviderFailed("Oxylabs returned nothing");
            }
            return res;
        } catch (RestClientException ex) {
            throw new ProviderFailed(label(), ex);
        }
    }

    static boolean onDomain(String url, String domain) {
        String host = domainOf(url);
        return host != null && (host.equals(domain) || host.endsWith("." + domain));
    }

    private static String domainOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

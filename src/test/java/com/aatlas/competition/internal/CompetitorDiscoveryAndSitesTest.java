package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aatlas.competition.internal.ShoppingProvider.Listing;
import com.aatlas.competition.internal.ShoppingProvider.Market;
import com.aatlas.competition.internal.TrackedCompetitors.Tracked;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DataForSEO discovery and the Oxylabs competitor-site scrape, against a local server in their documented shapes. */
class CompetitorDiscoveryAndSitesTest {

    private HttpServer server;
    private String base;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String path, int status, java.util.function.Function<String, String> reply) {
        server.createContext(path, ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.merge(path, req, (a, b) -> a + "\n" + b);
            byte[] body = reply.apply(req).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    @Test
    void serpCompetitorsReadsDomainsAndCost() {
        answer("/v3/dataforseo_labs/google/serp_competitors/live", 200, req -> """
                {"status_code":20000,"status_message":"Ok.","cost":0.0105,"tasks":[{"status_code":20000,
                  "status_message":"Ok.","result":[{"total_count":2,"items":[
                   {"domain":"www.supplyhouse.com","avg_position":3,"median_position":3,"rating":90,"etv":1250.5,
                    "keywords_count":4,"visibility":0.62,"keywords_positions":{"pvc ball valve":[2],"copper coupling":[5]}},
                   {"domain":"homedepot.com","avg_position":5,"keywords_count":6,"etv":9000.0,"visibility":0.8,
                    "keywords_positions":{}}]}]}]}
                """);
        DataForSeoClient.Found f = new DataForSeoClient(base, "login", "pw")
                .serpCompetitors(List.of("pvc ball valve", "copper coupling"), 2840, 30);

        assertThat(f.cost()).isEqualByComparingTo("0.0105");
        assertThat(f.competitors()).extracting(DataForSeoClient.Competitor::domain)
                .containsExactly("www.supplyhouse.com", "homedepot.com");
        assertThat(f.competitors().get(0).keywordPositions()).containsEntry("pvc ball valve", List.of(2));
        assertThat(bodies.get("/v3/dataforseo_labs/google/serp_competitors/live"))
                .startsWith("[").contains("\"location_code\":2840", "\"item_types\":[\"organic\"]");
    }

    @Test
    void aFailedTaskInsideAnOkEnvelopeIsAFailure() {
        answer("/v3/dataforseo_labs/google/competitors_domain/live", 200, req -> """
                {"status_code":20000,"status_message":"Ok.","tasks":[{"status_code":40501,
                  "status_message":"Invalid Field: 'target'.","result":null}]}
                """);
        assertThatThrownBy(() -> new DataForSeoClient(base, "l", "p").domainCompetitors("x.com", 2840, 10))
                .isInstanceOf(ShoppingProvider.ProviderFailed.class).hasMessageContaining("40501");
    }

    @Test
    void anEmptyBalanceSaysSo() {
        answer("/v3/dataforseo_labs/google/competitors_domain/live", 402,
                req -> "{\"status_code\":40200,\"status_message\":\"Payment Required.\"}");
        assertThatThrownBy(() -> new DataForSeoClient(base, "l", "p").domainCompetitors("x.com", 2840, 10))
                .hasMessageContaining("Payment Required");
    }

    @Test
    void competitorSiteFindsThePageOnItsOwnDomainAndReadsItsPrice() {
        answer("/v1/queries", 200, req -> req.contains("google_search") ? """
                {"results":[{"content":{"results":{"organic":[
                  {"pos":1,"url":"https://www.pinterest.com/pin/1","title":"x"},
                  {"pos":2,"url":"https://www.supplyhouse.com/p/pvc-ball-valve-12","title":"PVC Ball Valve"}]}}}]}
                """ : """
                {"results":[{"status_code":200,"content":"<html><script type=\\"application/ld+json\\">{\\"@type\\":\\"Product\\",\\"name\\":\\"1/2 in PVC Ball Valve\\",\\"offers\\":{\\"price\\":\\"5.79\\",\\"priceCurrency\\":\\"USD\\"}}</script></html>"}]}
                """);
        Tracked t = new Tracked(UUID.randomUUID(), "supplyhouse.com", "SupplyHouse", "manual", true, null);
        List<CompetitorSites.SiteResult> out = new CompetitorSites(base, "u", "p")
                .searchSites("1/2 in pvc ball valve", Market.of("US"), List.of(t));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).problem()).isNull();
        assertThat(out.get(0).listings()).containsExactly(new Listing("site", "1/2 in PVC Ball Valve",
                new BigDecimal("5.79"), "USD", "SupplyHouse", "https://www.supplyhouse.com/p/pvc-ball-valve-12"));
        assertThat(bodies.get("/v1/queries")).contains("site:supplyhouse.com 1/2 in pvc ball valve", "\"universal\"");
    }

    @Test
    void aSiteWithNoMatchingPageIsReportedNotFailed() {
        answer("/v1/queries", 200, req -> "{\"results\":[{\"content\":{\"results\":{\"organic\":[]}}}]}");
        Tracked t = new Tracked(UUID.randomUUID(), "zoro.com", "Zoro", "manual", true, null);
        CompetitorSites.SiteResult r = new CompetitorSites(base, "u", "p")
                .searchSites("widget", Market.of("US"), List.of(t)).get(0);
        assertThat(r.listings()).isEmpty();
        assertThat(r.problem()).startsWith("No product page found");
    }

    @Test
    void domainsAreNormalisedAndClassified() {
        assertThat(TrackedCompetitors.normalise("https://www.HomeDepot.com/p/123")).isEqualTo("homedepot.com");
        assertThat(TrackedCompetitors.normalise("zoro.com")).isEqualTo("zoro.com");
        assertThatThrownBy(() -> TrackedCompetitors.normalise("not a domain")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CompetitionService.kindOf("amazon.com")).isEqualTo("marketplace");
        assertThat(CompetitionService.kindOf("en.wikipedia.org")).isEqualTo("not-a-store");
        assertThat(CompetitionService.kindOf("supplyhouse.com")).isEqualTo("store");
        assertThat(CompetitionService.nameOf("supplyhouse.com")).isEqualTo("Supplyhouse");
        assertThat(CompetitorSites.onDomain("https://shop.zoro.com/x", "zoro.com")).isTrue();
        assertThat(CompetitorSites.onDomain("https://notzoro.com/x", "zoro.com")).isFalse();
    }

    @Test
    void oneStoreSpelledThreeWaysIsOneSeller() {
        Listing a = new Listing("serpapi", "t", BigDecimal.ONE, "USD", "The Home Depot", null);
        Listing b = new Listing("site", "t", BigDecimal.ONE, "USD", "homedepot.com", null);
        Listing c = new Listing("site", "t", BigDecimal.ONE, "USD", "Home Depot", null);
        assertThat(ListingFilter.seller(a)).isEqualTo(ListingFilter.seller(b)).isEqualTo(ListingFilter.seller(c));
        assertThat(ListingFilter.seller(new Listing("ebay", "t", BigDecimal.ONE, "USD", "eBay: The Home Depot", null)))
                .isNotEqualTo(ListingFilter.seller(a));
    }
}

package com.aatlas.competition.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aatlas.competition.internal.ShoppingProvider.Listing;
import com.aatlas.competition.internal.ShoppingProvider.Market;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each provider against a local server answering in the provider's documented response shape:
 * the request goes where the docs say, and the listing comes back with the right price,
 * currency, seller and link. No network, no keys.
 */
class ProviderParsingTest {

    private static final Market US = Market.of("US");

    private HttpServer server;
    private String base;
    private final Map<String, String> requests = new ConcurrentHashMap<>();

    private void answer(String path, String json) {
        server.createContext(path, ex -> {
            requests.put(path, ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders()
                    .getFirst("Authorization") + " market=" + ex.getRequestHeaders().getFirst("X-EBAY-C-MARKETPLACE-ID"));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

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

    @Test
    void serpApiGoogleShopping() {
        answer("/search.json", """
                {"shopping_results":[{"position":1,"title":"Charlotte 1/2 in. PVC Ball Valve","source":"The Home Depot",
                  "price":"$6.48","extracted_price":6.48,"product_link":"https://www.google.com/search?ibp=oshop&prds=1"}]}
                """);
        List<Listing> out = new SerpApiGoogleShopping(base, "k").search("1/2 in pvc ball valve", US, 10);

        assertThat(out).containsExactly(new Listing("serpapi", "Charlotte 1/2 in. PVC Ball Valve",
                new java.math.BigDecimal("6.48"), "USD", "The Home Depot", "https://www.google.com/search?ibp=oshop&prds=1"));
        assertThat(requests.get("/search.json")).contains("engine=google_shopping", "gl=us", "api_key=k");
    }

    @Test
    void serpApiNoResultsIsAnAnswerAndABadKeyIsAFailure() {
        answer("/search.json", "{\"error\":\"Google hasn't returned any results for this query.\"}");
        assertThat(new SerpApiGoogleShopping(base, "k").search("zzz", US, 10)).isEmpty();
    }

    @Test
    void ebayBrowseFetchesATokenThenSearchesNewListings() {
        answer("/identity/v1/oauth2/token", "{\"access_token\":\"tok\",\"expires_in\":7200}");
        answer("/buy/browse/v1/item_summary/search", """
                {"itemSummaries":[{"title":"1/2 PVC Ball Valve","price":{"value":"5.25","currency":"USD"},
                  "seller":{"username":"acme_supply"},"itemWebUrl":"https://www.ebay.com/itm/1"}]}
                """);
        List<Listing> out = new EbayBrowse(base, "id", "secret").search("1/2 pvc ball valve", US, 10);

        assertThat(out).containsExactly(new Listing("ebay", "1/2 PVC Ball Valve", new java.math.BigDecimal("5.25"),
                "USD", "eBay", "https://www.ebay.com/itm/1"));
        String search = requests.get("/buy/browse/v1/item_summary/search");
        assertThat(search).contains("auth=Bearer tok", "market=EBAY_US", "%7BNEW%7D");
        assertThat(requests.get("/identity/v1/oauth2/token")).startsWith("POST").contains("auth=Basic ");
    }

    @Test
    void rainforestAmazonReadsThePriceObjectAndFallsBackToPrices() {
        answer("/request", """
                {"search_results":[
                  {"title":"PVC Ball Valve 1/2","link":"https://www.amazon.com/dp/A","price":{"value":7.99,"currency":"USD"}},
                  {"title":"PVC Ball Valve 1/2 2-pack","link":"https://www.amazon.com/dp/B","prices":[{"value":12.5,"currency":"USD"}]},
                  {"title":"Unavailable valve","link":"https://www.amazon.com/dp/C"}]}
                """);
        List<Listing> out = new RainforestAmazon(base, "k").search("pvc ball valve", US, 10);

        assertThat(out).extracting(Listing::price).containsExactly(new java.math.BigDecimal("7.99"),
                new java.math.BigDecimal("12.5"), null);
        assertThat(out).allMatch(l -> "Amazon".equals(l.merchant()));
        assertThat(requests.get("/request")).contains("type=search", "amazon_domain=amazon.com");
    }

    @Test
    void oxylabsGoogleShoppingReadsParsedOrganicResults() {
        answer("/v1/queries", """
                {"results":[{"content":{"results":{"organic":[{"pos":1,"title":"1/2 in PVC Ball Valve","price":6.2,
                  "currency":"USD","merchant":{"name":"Lowe's"},"url":"https://www.google.com/shopping/product/1"}]}}}]}
                """);
        List<Listing> out = new OxylabsGoogleShopping(base, "u", "p").search("pvc ball valve", US, 10);

        assertThat(out).containsExactly(new Listing("oxylabs", "1/2 in PVC Ball Valve", new java.math.BigDecimal("6.2"),
                "USD", "Lowe's", "https://www.google.com/shopping/product/1"));
        assertThat(requests.get("/v1/queries")).startsWith("POST").contains("auth=Basic ");
    }

    @Test
    void aProviderWithNoKeyIsUnavailableAndAServerErrorIsAFailure() {
        assertThat(new SerpApiGoogleShopping(base, "").available()).isFalse();
        assertThat(new EbayBrowse(base, "id", "").available()).isFalse();
        assertThat(new RainforestAmazon(base, " ").available()).isFalse();
        assertThat(new OxylabsGoogleShopping(base, "u", null).available()).isFalse();

        server.createContext("/request", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        assertThatThrownBy(() -> new RainforestAmazon(base, "bad").search("x", US, 5))
                .isInstanceOf(ShoppingProvider.ProviderFailed.class);
    }
}

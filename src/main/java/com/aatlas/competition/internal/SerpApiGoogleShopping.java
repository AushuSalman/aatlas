package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google Shopping through SerpApi ({@code engine=google_shopping},
 * <a href="https://serpapi.com/google-shopping-api">serpapi.com/google-shopping-api</a>).
 *
 * <p>One GET per search, the key as a query parameter. Each of {@code shopping_results[]} gives
 * the store ({@code source}), a numeric {@code extracted_price} and a {@code product_link}. There
 * is no currency field: the storefront ({@code gl}) decides it, so the listing carries the
 * market's currency. The link is Google's product page, not the store's - the store is named.
 */
@Component
class SerpApiGoogleShopping implements ShoppingProvider {

    private final RestClient http;
    private final String apiKey;

    SerpApiGoogleShopping(
            @Value("${aatlas.competition.serpapi.base-url}") String baseUrl,
            @Value("${aatlas.competition.serpapi.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(ShoppingProvider.requestFactory()).build();
    }

    @Override
    public String key() {
        return "serpapi";
    }

    @Override
    public String label() {
        return "Google Shopping (SerpApi)";
    }

    @Override
    public String plan() {
        return "Every store Google Shopping lists: Home Depot, Lowe's, Grainger, Zoro, Amazon and more. "
                + "Free plan: 250 searches a month; paid from $25 a month for 1,000.";
    }

    @Override
    public boolean available() {
        return !ShoppingProvider.blank(apiKey);
    }

    @Override
    public List<Listing> search(String query, Market market, int max) {
        JsonNode body;
        try {
            body = http.get()
                    .uri(u -> u.path("/search.json")
                            .queryParam("engine", "google_shopping")
                            .queryParam("q", query)
                            .queryParam("gl", market.uk() ? "uk" : "us")
                            .queryParam("hl", "en")
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ProviderFailed(label(), ex);
        }
        if (body == null) {
            return List.of();
        }
        if (body.hasNonNull("error") && !body.path("shopping_results").isArray()) {
            // "Google hasn't returned any results for this query." is an answer, not a failure.
            String error = body.path("error").asText();
            if (error.toLowerCase().contains("hasn't returned any results")) {
                return List.of();
            }
            throw new ProviderFailed(label() + ": " + error);
        }
        List<Listing> out = new ArrayList<>();
        for (JsonNode r : body.path("shopping_results")) {
            if (out.size() >= max) {
                break;
            }
            out.add(new Listing(key(), r.path("title").asText(null),
                    ShoppingProvider.money(r.hasNonNull("extracted_price") ? r.get("extracted_price") : r.get("price")),
                    market.currency(), r.path("source").asText(null),
                    r.hasNonNull("product_link") ? r.get("product_link").asText() : r.path("link").asText(null)));
        }
        return out;
    }
}

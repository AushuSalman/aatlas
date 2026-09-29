package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Amazon search results through Rainforest API (Traject Data, {@code type=search}).
 *
 * <p>Search results carry no seller, so every listing is Amazon's: one competitor, whose
 * best-matching listing is the observation. {@code price} is an object ({@code value},
 * {@code currency}); {@code prices[0]} is the fallback, and an unavailable item has neither.
 */
@Component
class RainforestAmazon implements ShoppingProvider {

    private final RestClient http;
    private final String apiKey;

    RainforestAmazon(
            @Value("${aatlas.competition.rainforest.base-url}") String baseUrl,
            @Value("${aatlas.competition.rainforest.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(ShoppingProvider.requestFactory()).build();
    }

    @Override
    public String key() {
        return "rainforest";
    }

    @Override
    public String label() {
        return "Amazon (Rainforest API)";
    }

    @Override
    public String plan() {
        return "Amazon's own search results and prices. Paid: from about $23 a month for 500 requests.";
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
                    .uri(u -> u.path("/request")
                            .queryParam("api_key", apiKey)
                            .queryParam("type", "search")
                            .queryParam("amazon_domain", market.uk() ? "amazon.co.uk" : "amazon.com")
                            .queryParam("search_term", query)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ProviderFailed(label(), ex);
        }
        List<Listing> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode r : body.path("search_results")) {
            if (out.size() >= max) {
                break;
            }
            JsonNode price = r.hasNonNull("price") ? r.get("price") : r.path("prices").path(0);
            out.add(new Listing(key(), r.path("title").asText(null), ShoppingProvider.money(price.get("value")),
                    price.path("currency").asText(market.currency()), "Amazon", r.path("link").asText(null)));
        }
        return out;
    }
}

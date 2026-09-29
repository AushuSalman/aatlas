package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google Shopping through Oxylabs' Web Scraper API (realtime, {@code source=google_shopping_search},
 * parsed). The same shelf SerpApi reads, for a tenant that already has an Oxylabs account.
 *
 * <p>Basic auth with the API user; the parsed listings are at
 * {@code results[0].content.results.organic[]}, each with {@code price}, {@code currency},
 * {@code merchant.name} and {@code url}.
 */
@Component
class OxylabsGoogleShopping implements ShoppingProvider {

    private final RestClient http;
    private final String username;
    private final String password;

    OxylabsGoogleShopping(
            @Value("${aatlas.competition.oxylabs.base-url}") String baseUrl,
            @Value("${aatlas.competition.oxylabs.username}") String username,
            @Value("${aatlas.competition.oxylabs.password}") String password) {
        this.username = username;
        this.password = password;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(ShoppingProvider.requestFactory()).build();
    }

    @Override
    public String key() {
        return "oxylabs";
    }

    @Override
    public String label() {
        return "Google Shopping (Oxylabs)";
    }

    @Override
    public String plan() {
        return "Google Shopping, scraped by Oxylabs. Paid: free trial, then from $49 a month.";
    }

    @Override
    public boolean available() {
        return !ShoppingProvider.blank(username) && !ShoppingProvider.blank(password);
    }

    @Override
    public List<Listing> search(String query, Market market, int max) {
        String basic = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> request = Map.of(
                "source", "google_shopping_search",
                "query", query,
                "geo_location", market.uk() ? "United Kingdom" : "United States",
                "locale", market.uk() ? "en-gb" : "en-us",
                "parse", true);
        JsonNode body;
        try {
            body = http.post()
                    .uri("/v1/queries")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ProviderFailed(label(), ex);
        }
        List<Listing> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode r : body.path("results").path(0).path("content").path("results").path("organic")) {
            if (out.size() >= max) {
                break;
            }
            out.add(new Listing(key(), r.path("title").asText(null), ShoppingProvider.money(r.get("price")),
                    r.path("currency").asText(market.currency()), r.path("merchant").path("name").asText(null),
                    r.path("url").asText(null)));
        }
        return out;
    }
}

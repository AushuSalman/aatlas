package com.aatlas.marketdata.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * FRED, the St. Louis Fed's economic data API ({@code /fred/series/observations}). Free, with a
 * key from fredaccount.stlouisfed.org; 120 requests a minute, far above a daily refresh of a
 * handful of series.
 *
 * <p>Values come back as strings, {@code "."} for a month with no figure - skipped here.
 */
@Component
class FredClient {

    private final RestClient http;
    private final String apiKey;

    FredClient(@Value("${aatlas.market-data.fred.base-url}") String baseUrl,
            @Value("${aatlas.market-data.fred.api-key}") String apiKey) {
        this.apiKey = apiKey;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    record Observation(LocalDate date, BigDecimal value) {
    }

    /** The latest {@code limit} observations with a value, newest first. */
    List<Observation> latest(String seriesId, int limit) {
        JsonNode body;
        try {
            body = http.get()
                    .uri(u -> u.path("/fred/series/observations")
                            .queryParam("series_id", seriesId)
                            .queryParam("api_key", apiKey)
                            .queryParam("file_type", "json")
                            .queryParam("sort_order", "desc")
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            // FRED explains a bad key or series in the body: {"error_code":400,"error_message":"..."}
            String why = ex.getResponseBodyAsString();
            throw new FeedFailed("FRED " + seriesId + ": " + (why.isBlank() ? ex.getMessage() : why));
        } catch (RestClientException ex) {
            throw new FeedFailed("FRED " + seriesId + ": " + ex.getMessage());
        }
        List<Observation> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode o : body.path("observations")) {
            String v = o.path("value").asText(".");
            if (".".equals(v) || v.isBlank()) {
                continue;
            }
            try {
                out.add(new Observation(LocalDate.parse(o.path("date").asText()), new BigDecimal(v)));
            } catch (RuntimeException ignored) {
                // a malformed row is skipped, not the series
            }
        }
        return out;
    }

    static class FeedFailed extends RuntimeException {
        FeedFailed(String message) {
            super(message);
        }
    }
}

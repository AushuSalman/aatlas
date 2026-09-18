package com.aatlas.prices.internal;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Web search for the AI price-research pilot, over Tavily's plain REST API
 * (<a href="https://docs.tavily.com">docs.tavily.com</a>) - no SDK, no CLI, nothing installed
 * on the machine this runs on: one POST per search, a bearer token, JSON in and out.
 *
 * <p>{@code aatlas.tavily.api-key} is empty by default (see {@code .env.example}); when it
 * is, {@link #search} throws {@link TavilyUnavailable} rather than making a request that can
 * only fail, so a caller can show "not configured" instead of a network error.
 */
@Component
class TavilyClient {

    private static final Logger log = LoggerFactory.getLogger(TavilyClient.class);

    private final RestClient http;
    private final String apiKey;

    TavilyClient(
            @Value("${aatlas.tavily.base-url}") String baseUrl,
            @Value("${aatlas.tavily.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(clientRequestFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * One search. {@code maxResults} is capped at 10 (Tavily's own basic-plan ceiling);
     * {@code includeAnswer} asks Tavily's own model for a one-line synthesis on top of the
     * raw results, which the price-extraction step can use as a second opinion.
     */
    Response search(String query, int maxResults, boolean includeAnswer) {
        if (!available()) {
            throw new TavilyUnavailable();
        }
        Request body = new Request(query, "basic", Math.min(10, Math.max(1, maxResults)),
                includeAnswer, false);
        try {
            Response res = http.post()
                    .uri("/search")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Response.class);
            return res == null ? new Response(query, null, List.of()) : res;
        } catch (org.springframework.web.client.RestClientException ex) {
            log.warn("Tavily search failed for \"{}\": {}", query, ex.getMessage());
            throw new TavilySearchFailed(ex);
        }
    }

    record Request(String query, String search_depth, int max_results, boolean include_answer,
            boolean include_raw_content) {
    }

    record Response(String query, String answer, List<Result> results) {
    }

    // No `score` field: it is Tavily's own relevance ranking, a float this module never
    // reads, and `prices..` is barred from java.lang.Double (see ArchitectureRulesTest's
    // noFloatingPointMoney) - not worth a BigDecimal for a number nothing here uses.
    record Result(String title, String url, String content) {
    }

    static class TavilyUnavailable extends RuntimeException {
        TavilyUnavailable() {
            super("Tavily is not configured (aatlas.tavily.api-key is empty).");
        }
    }

    static class TavilySearchFailed extends RuntimeException {
        TavilySearchFailed(Throwable cause) {
            super("Tavily search failed: " + cause.getMessage(), cause);
        }
    }
}

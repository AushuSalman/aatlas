package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * DataForSEO Labs - who competes for a tenant's items online (docs.dataforseo.com/v3).
 *
 * <p>Two live endpoints, one task each: {@code serp_competitors} (the domains that rank on Google
 * for a list of keywords - the item descriptions) and {@code competitors_domain} (the domains that
 * share the most keywords with the tenant's own site). Basic auth with the API login and the
 * system-generated API password from app.dataforseo.com/api-access. Every response carries its
 * {@code cost}, which is passed through rather than assumed. A failed task can come back inside
 * an HTTP 200 with a top-level 20000, so both status codes are checked.
 */
@Component
class DataForSeoClient {

    private final RestClient http;
    private final String login;
    private final String password;

    DataForSeoClient(@Value("${aatlas.competition.dataforseo.base-url}") String baseUrl,
            @Value("${aatlas.competition.dataforseo.login}") String login,
            @Value("${aatlas.competition.dataforseo.password}") String password) {
        this.login = login;
        this.password = password;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    boolean available() {
        return login != null && !login.isBlank() && password != null && !password.isBlank();
    }

    /**
     * One competing domain.
     *
     * @param keywordsCount how many of the keywords it ranks for ({@code intersections} for a domain search)
     * @param etv           estimated monthly organic traffic from those keywords
     * @param keywordPositions keyword → its positions; empty for a domain search
     */
    record Competitor(String domain, BigDecimal avgPosition, Integer keywordsCount, BigDecimal visibility,
            BigDecimal etv, Map<String, List<Integer>> keywordPositions) {
    }

    record Found(List<Competitor> competitors, BigDecimal cost) {
    }

    /** The domains ranking on Google for these keywords (organic results only - ads are not stores we can price). */
    Found serpCompetitors(List<String> keywords, int locationCode, int limit) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("keywords", keywords.stream().limit(200).toList());
        task.put("location_code", locationCode);
        task.put("language_code", "en");
        task.put("item_types", List.of("organic"));
        task.put("include_subdomains", false);
        task.put("limit", Math.max(1, Math.min(limit, 1000)));
        Reply reply = call("/v3/dataforseo_labs/google/serp_competitors/live", task);
        JsonNode result = reply.result();
        List<Competitor> out = new ArrayList<>();
        for (JsonNode i : result.path("items")) {
            Map<String, List<Integer>> positions = new LinkedHashMap<>();
            i.path("keywords_positions").fields().forEachRemaining(e -> {
                List<Integer> ps = new ArrayList<>();
                e.getValue().forEach(p -> ps.add(p.asInt()));
                positions.put(e.getKey(), ps);
            });
            out.add(new Competitor(i.path("domain").asText(), dec(i.get("avg_position")),
                    i.hasNonNull("keywords_count") ? i.get("keywords_count").asInt() : null, dec(i.get("visibility")),
                    dec(i.get("etv")), positions));
        }
        return new Found(out, reply.cost());
    }

    /** The domains sharing the most Google keywords with {@code target}. */
    Found domainCompetitors(String target, int locationCode, int limit) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("target", target);
        task.put("location_code", locationCode);
        task.put("language_code", "en");
        task.put("item_types", List.of("organic"));
        task.put("exclude_top_domains", true);
        task.put("limit", Math.max(1, Math.min(limit, 1000)));
        Reply reply = call("/v3/dataforseo_labs/google/competitors_domain/live", task);
        JsonNode result = reply.result();
        List<Competitor> out = new ArrayList<>();
        for (JsonNode i : result.path("items")) {
            JsonNode organic = i.path("metrics").path("organic");
            out.add(new Competitor(i.path("domain").asText(), dec(i.get("avg_position")),
                    i.hasNonNull("intersections") ? i.get("intersections").asInt() : null, null,
                    dec(organic.get("etv")), Map.of()));
        }
        return new Found(out, reply.cost());
    }

    private record Reply(JsonNode result, BigDecimal cost) {
    }


    private Reply call(String path, Map<String, Object> task) {
        if (!available()) {
            throw new ShoppingProvider.ProviderFailed(
                    "DataForSEO is not configured (DATAFORSEO_LOGIN and DATAFORSEO_PASSWORD).");
        }
        String basic = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        JsonNode body;
        try {
            body = http.post()
                    .uri(path)
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(task))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new ShoppingProvider.ProviderFailed("DataForSEO: " + envelopeMessage(ex.getResponseBodyAsString(),
                    ex.getStatusText()));
        } catch (RestClientException ex) {
            throw new ShoppingProvider.ProviderFailed("DataForSEO", ex);
        }
        if (body == null) {
            throw new ShoppingProvider.ProviderFailed("DataForSEO: empty response");
        }
        if (body.path("status_code").asInt() != 20000) {
            throw new ShoppingProvider.ProviderFailed("DataForSEO: " + body.path("status_code").asInt() + " "
                    + body.path("status_message").asText());
        }
        JsonNode t = body.path("tasks").path(0);
        if (t.path("status_code").asInt() != 20000) {
            throw new ShoppingProvider.ProviderFailed("DataForSEO: " + t.path("status_code").asInt() + " "
                    + t.path("status_message").asText());
        }
        return new Reply(t.path("result").path(0), dec(body.get("cost")));
    }

    private static String envelopeMessage(String body, String fallback) {
        int i = body == null ? -1 : body.indexOf("\"status_message\"");
        if (i < 0) {
            return fallback;
        }
        int start = body.indexOf('"', body.indexOf(':', i) + 1) + 1;
        int end = body.indexOf('"', start);
        return start > 0 && end > start ? body.substring(start, end) : fallback;
    }

    private static BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.decimalValue();
    }
}

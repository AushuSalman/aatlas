package com.aatlas.marketdata.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Frankfurter: the European Central Bank's daily reference rates as JSON, free and keyless.
 * One GET for every currency against USD: {@code {"base":"USD","date":"...","rates":{"GBP":0.78,...}}}.
 * A currency the ECB does not publish (VND, for one) is simply absent from {@code rates}.
 */
@Component
class FrankfurterClient {

    private final RestClient http;

    FrankfurterClient(@Value("${aatlas.market-data.fx.base-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    record Rates(LocalDate date, Map<String, BigDecimal> perUsd) {
    }

    Rates latestPerUsd(Collection<String> quotes) {
        JsonNode body;
        try {
            body = http.get()
                    .uri(u -> u.path("/latest")
                            .queryParam("base", "USD")
                            .queryParam("symbols", String.join(",", quotes))
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FredClient.FeedFailed("Frankfurter: " + ex.getMessage());
        }
        if (body == null || !body.hasNonNull("date")) {
            throw new FredClient.FeedFailed("Frankfurter: no rates in the response");
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        body.path("rates").fields().forEachRemaining(e -> {
            if (e.getValue().isNumber() && e.getValue().decimalValue().signum() > 0) {
                out.put(e.getKey(), e.getValue().decimalValue());
            }
        });
        return new Rates(LocalDate.parse(body.get("date").asText()), out);
    }
}

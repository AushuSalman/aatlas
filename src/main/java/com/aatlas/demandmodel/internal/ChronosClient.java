package com.aatlas.demandmodel.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Chronos, a pretrained time-series model, as one more forecaster in the contest.
 *
 * <p>It is not trained here: it runs in its own small service ({@code scripts/chronos_server.py}) and is asked
 * over HTTP for the weekly units it expects after each series it is given. With no URL configured, or with the
 * service down or answering nonsense, the contest simply runs without it: a training run never fails because
 * of it.
 */
@Component
class ChronosClient implements DemandTrainer.Outside {

    private static final Logger log = LoggerFactory.getLogger(ChronosClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    ChronosClient(@Value("${aatlas.demand-model.chronos.url:}") String url,
            @Value("${aatlas.demand-model.chronos.timeout-seconds:120}") int timeoutSeconds) {
        if (url == null || url.isBlank()) {
            this.http = null;
            return;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.http = RestClient.builder().baseUrl(url.trim()).requestFactory(factory).build();
    }

    /** This forecaster when a service is configured; null when not, which the trainer reads as "not in the contest". */
    DemandTrainer.Outside orNull() {
        return http == null ? null : this;
    }

    @Override
    public double[] forecast(List<double[]> series, int horizon) {
        if (http == null || series.isEmpty()) {
            return null;
        }
        try {
            // Written out first, so the request carries its length instead of going out in chunks.
            String request = JSON.writeValueAsString(Map.of("contexts", series, "horizon", horizon));
            JsonNode body = http.post()
                    .uri("/forecast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode means = body == null ? null : body.get("means");
            if (means == null || !means.isArray() || means.size() != series.size()) {
                log.warn("Chronos answered {} forecasts for {} series; the contest runs without it",
                        means == null ? 0 : means.size(), series.size());
                return null;
            }
            double[] out = new double[means.size()];
            for (int i = 0; i < out.length; i++) {
                double v = means.get(i).asDouble(Double.NaN);
                if (!Double.isFinite(v)) {
                    log.warn("Chronos answered a forecast that is not a number; the contest runs without it");
                    return null;
                }
                out[i] = Math.max(0, v);
            }
            return out;
        } catch (RestClientException | JsonProcessingException ex) {
            log.warn("Chronos is not answering ({}); the contest runs without it", ex.getMessage());
            return null;
        }
    }
}

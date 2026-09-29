package com.aatlas.marketdata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FRED and Frankfurter against a local server in their documented shapes, and the 3-month move. */
class MarketDataFeedsTest {

    private HttpServer server;
    private String base;
    private final Map<String, String> requests = new ConcurrentHashMap<>();

    private void answer(String path, int status, String json) {
        server.createContext(path, ex -> {
            requests.put(path, ex.getRequestURI().toString());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
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
    void fredReadsObservationsNewestFirstAndSkipsMissingMonths() {
        answer("/fred/series/observations", 200, """
                {"count":4,"observations":[
                  {"realtime_start":"2026-09-28","realtime_end":"2026-09-28","date":"2026-08-01","value":"530.2"},
                  {"date":"2026-07-01","value":"."},
                  {"date":"2026-06-01","value":"515.0"},
                  {"date":"2026-05-01","value":"500.0"}]}
                """);
        List<FredClient.Observation> obs = new FredClient(base, "k").latest("WPU102502", 12);

        assertThat(obs).extracting(FredClient.Observation::date).containsExactly(
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-06-01"), LocalDate.parse("2026-05-01"));
        assertThat(requests.get("/fred/series/observations")).contains("series_id=WPU102502", "file_type=json",
                "sort_order=desc", "api_key=k");
    }

    @Test
    void fredErrorCarriesFredsOwnMessage() {
        answer("/fred/series/observations", 400,
                "{\"error_code\":400,\"error_message\":\"Bad Request.  The value for variable api_key is not registered.\"}");
        assertThatThrownBy(() -> new FredClient(base, "bad").latest("WPU1017", 12))
                .isInstanceOf(FredClient.FeedFailed.class)
                .hasMessageContaining("api_key is not registered");
    }

    @Test
    void moveIsTheLatestMonthAgainstThreeMonthsEarlier() {
        MarketDataService.Move m = MarketDataService.move(List.of(
                new FredClient.Observation(LocalDate.parse("2026-08-01"), new BigDecimal("530.2")),
                new FredClient.Observation(LocalDate.parse("2026-06-01"), new BigDecimal("515.0")),
                new FredClient.Observation(LocalDate.parse("2026-05-01"), new BigDecimal("500.0"))));
        assertThat(m.pct()).isEqualByComparingTo("6.04");
        assertThat(m.asOf()).isEqualTo(LocalDate.parse("2026-08-01"));

        // A gap at the three-month mark falls back to the nearest earlier month.
        MarketDataService.Move gap = MarketDataService.move(List.of(
                new FredClient.Observation(LocalDate.parse("2026-08-01"), new BigDecimal("90")),
                new FredClient.Observation(LocalDate.parse("2026-04-01"), new BigDecimal("100"))));
        assertThat(gap.pct()).isEqualByComparingTo("-10.00");

        assertThat(MarketDataService.move(List.of(
                new FredClient.Observation(LocalDate.parse("2026-08-01"), new BigDecimal("90"))))).isNull();
    }

    @Test
    void frankfurterReadsUsdRates() {
        answer("/latest", 200, """
                {"amount":1.0,"base":"USD","date":"2026-09-25","rates":{"EUR":0.87696,"GBP":0.75458,"INR":95.82}}
                """);
        FrankfurterClient.Rates r = new FrankfurterClient(base).latestPerUsd(List.of("EUR", "GBP", "INR", "VND"));

        assertThat(r.date()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(r.perUsd()).containsOnlyKeys("EUR", "GBP", "INR");
        assertThat(r.perUsd().get("GBP")).isEqualByComparingTo("0.75458");
        assertThat(requests.get("/latest")).contains("base=USD", "symbols=EUR,GBP,INR,VND");
    }
}

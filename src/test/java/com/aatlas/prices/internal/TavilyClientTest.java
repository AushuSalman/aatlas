package com.aatlas.prices.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A real call against Tavily's live API - no mock, no Spring context, just the plain HTTP
 * client. Gated on {@code TAVILY_API_KEY} actually being set, so {@code mvn test} skips it
 * (rather than fails) on every machine that has not opted into the pilot, and it never
 * becomes a flaky "the internet was down" failure in a run nobody meant to hit the network.
 */
@EnabledIfEnvironmentVariable(named = "TAVILY_API_KEY", matches = ".+")
class TavilyClientTest {

    private final TavilyClient client = new TavilyClient(
            "https://api.tavily.com", System.getenv("TAVILY_API_KEY"));

    @Test
    void searchReturnsRealResults() {
        assertThat(client.available()).isTrue();

        TavilyClient.Response res = client.search(
                "1/2 inch copper pipe price per foot plumbing supply", 5, true);

        assertThat(res.results()).isNotEmpty();
        for (TavilyClient.Result r : res.results()) {
            assertThat(r.url()).startsWith("http");
            assertThat(r.title()).isNotBlank();
        }
        System.out.println("Tavily answer: " + res.answer());
        res.results().forEach(r -> System.out.println(" - " + r.title() + " -> " + r.url()));
    }

    @Test
    void unavailableWithNoKeyThrowsRatherThanCallingOut() {
        TavilyClient noKey = new TavilyClient("https://api.tavily.com", "");
        assertThat(noKey.available()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> noKey.search("anything", 3, false))
                .isInstanceOf(TavilyClient.TavilyUnavailable.class);
    }
}

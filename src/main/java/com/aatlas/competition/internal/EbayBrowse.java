package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * eBay's Browse API ({@code /buy/browse/v1/item_summary/search}) - free with an eBay developer
 * account, 5,000 calls a day.
 *
 * <p>An application token first (OAuth client credentials, the public {@code api_scope}),
 * cached until a minute before it expires; then one GET per search, new condition only so a
 * used part never sets a new price. {@code price.value} is a string on this API.
 *
 * <p>Every listing's merchant is "eBay": the seller's username is never read, so nothing about
 * an eBay user is stored anywhere.
 */
@Component
class EbayBrowse implements ShoppingProvider {

    private static final String SCOPE = "https://api.ebay.com/oauth/api_scope";

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    /** Token expiry is wall-clock bookkeeping against eBay's server, not a business "now". */
    private final Clock clock = Clock.systemUTC();

    private volatile String token;
    private volatile Instant tokenExpires = Instant.EPOCH;

    EbayBrowse(
            @Value("${aatlas.competition.ebay.base-url}") String baseUrl,
            @Value("${aatlas.competition.ebay.client-id}") String clientId,
            @Value("${aatlas.competition.ebay.client-secret}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(ShoppingProvider.requestFactory()).build();
    }

    @Override
    public String key() {
        return "ebay";
    }

    @Override
    public String label() {
        return "eBay (Browse API)";
    }

    @Override
    public String plan() {
        return "New-condition fixed-price listings on eBay, including trade and industrial sellers. "
                + "Free with an eBay developer account: 5,000 calls a day.";
    }

    @Override
    public boolean available() {
        return !ShoppingProvider.blank(clientId) && !ShoppingProvider.blank(clientSecret);
    }

    @Override
    public List<Listing> search(String query, Market market, int max) {
        JsonNode body;
        try {
            String bearer = token();
            body = http.get()
                    .uri(u -> u.path("/buy/browse/v1/item_summary/search")
                            .queryParam("q", "{q}")
                            .queryParam("limit", Math.min(50, Math.max(1, max)))
                            .queryParam("filter", "{filter}")
                            .build(query, "conditions:{NEW},priceCurrency:" + market.currency()))
                    .header("Authorization", "Bearer " + bearer)
                    .header("X-EBAY-C-MARKETPLACE-ID", market.uk() ? "EBAY_GB" : "EBAY_US")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ProviderFailed(label(), ex);
        }
        List<Listing> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode r : body.path("itemSummaries")) {
            if (out.size() >= max) {
                break;
            }
            JsonNode price = r.path("price");
            // "eBay", never the seller's username: no eBay user data is kept, which is what lets the
            // Production keyset claim the Marketplace Account Deletion exemption. eBay is one competitor.
            out.add(new Listing(key(), r.path("title").asText(null), ShoppingProvider.money(price.get("value")),
                    price.path("currency").asText(market.currency()), "eBay", r.path("itemWebUrl").asText(null)));
        }
        return out;
    }

    private String token() {
        if (token != null && clock.instant().isBefore(tokenExpires)) {
            return token;
        }
        synchronized (this) {
            if (token != null && clock.instant().isBefore(tokenExpires)) {
                return token;
            }
            String basic = Base64.getEncoder()
                    .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            JsonNode res = http.post()
                    .uri("/identity/v1/oauth2/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials&scope=" + java.net.URLEncoder.encode(SCOPE, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null || !res.hasNonNull("access_token")) {
                throw new ProviderFailed(label() + ": eBay returned no access token");
            }
            token = res.get("access_token").asText();
            tokenExpires = clock.instant().plusSeconds(Math.max(60, res.path("expires_in").asLong(7200) - 60));
            return token;
        }
    }
}

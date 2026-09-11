package com.aatlas.sell;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Every {@code /api/v1/sell/*} endpoint against a real PostgreSQL: signup, connect the
 * sample dataset, exercise every read and both writes on the flagship pair (Copper Tube
 * 1/2" at Dallas #100959 - HRD118902/100959, its own default branch so it is priceable
 * everywhere without hunting for one).
 *
 * <p>Golden-file precision is {@code com.aatlas.sell.internal.engine.*GoldenTest}'s job
 * (pure unit tests, no Spring context); this class is the wiring - real Postgres rows
 * through {@code CatalogGateway}/{@code GuardrailsGateway}, a real JWT, the actual
 * controllers - the way {@code SuppliersIT}/{@code DataSourceIT} exercise wave 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-sell-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SellIT extends PostgresIntegrationTest {

    private static final String ITEM = "HRD118902";
    private static final String STORE = "100959";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String token;

    private static String uniqueEmail() {
        return "seller-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUpAndConnect() throws Exception {
        String body = """
                {
                  "fullName": "Dana Whitfield",
                  "email": "%s",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "both"
                }
                """.formatted(uniqueEmail());
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        return accessToken;
    }

    @BeforeAll
    void setUp() throws Exception {
        token = signUpAndConnect();
    }

    @Test
    @DisplayName("a workspace with no catalogue refuses every read with 409 no_catalogue")
    void noCatalogueIsRefused() throws Exception {
        String freshToken = signUpAndConnectless();
        mvc.perform(get("/api/v1/sell/recommendation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
    }

    private String signUpAndConnectless() throws Exception {
        String body = """
                {
                  "fullName": "No Catalogue Yet",
                  "email": "%s",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "both"
                }
                """.formatted(uniqueEmail());
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("GET /sell/recommendation: the whole answer for the flagship pair")
    void recommendation() throws Exception {
        // The frontend's SellRecommendationResponse (platform/backend.ts) is declared
        // `extends SellIntel`: every SellIntel field belongs at the top level of this
        // response, not nested under "intel" - a prior version nested it, which made
        // `priceable` (and everything else) read as undefined on the frontend and showed
        // "no sales history" for lines that were priceable. Assert the flattened shape the
        // frontend actually reads, not just that the backend has internally-consistent data.
        MvcResult result = mvc.perform(get("/api/v1/sell/recommendation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceable").value(true))
                .andExpect(jsonPath("$.itemNumber").value(ITEM))
                .andExpect(jsonPath("$.recommended").isNumber())
                .andExpect(jsonPath("$.guardrail").exists())
                .andExpect(jsonPath("$.decisionScore.total").isNumber())
                .andExpect(jsonPath("$.speedPricing.tiers.length()").value(3))
                .andExpect(jsonPath("$.holdVsSell.recommendation").isNotEmpty())
                .andExpect(jsonPath("$.opportunityScore.score").isNumber())
                .andExpect(jsonPath("$.intel").doesNotExist())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assert body.get("recommended").isNumber();
    }

    @Test
    @DisplayName("GET /sell/recommendation/derivation: both tiers, benchmarks, bands, steps, weights")
    void derivation() throws Exception {
        mvc.perform(get("/api/v1/sell/recommendation/derivation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimal.price").isNumber())
                .andExpect(jsonPath("$.aggressive.price").isNumber())
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.weights").isArray());
    }

    @Test
    @DisplayName("GET /sell/score: the opportunity chip")
    void score() throws Exception {
        mvc.perform(get("/api/v1/sell/score?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.tier").isNotEmpty())
                .andExpect(jsonPath("$.reasons").isArray());
    }

    @Test
    @DisplayName("GET /sell/forecast and /sell/elasticity: the two files this track also ports")
    void forecastAndElasticity() throws Exception {
        mvc.perform(get("/api/v1/sell/forecast?item=" + ITEM + "&store=" + STORE + "&horizon=operational")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceable").value(true))
                .andExpect(jsonPath("$.points.length()").value(6));

        mvc.perform(get("/api/v1/sell/elasticity?item=" + ITEM + "&side=sell&counterparty=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coefficient").isNumber())
                .andExpect(jsonPath("$.curve").isArray());
    }

    @Test
    @DisplayName("POST /sell/scenario: a price move and a named what-if")
    void scenario() throws Exception {
        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"pct\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMove.price").isNumber())
                .andExpect(jsonPath("$.scenario").doesNotExist());

        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"scenario\":\"hold-30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario.title").isNotEmpty());

        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    @DisplayName("GET /sell/hold-vs-sell and /sell/speed-tiers")
    void holdVsSellAndSpeedTiers() throws Exception {
        mvc.perform(get("/api/v1/sell/hold-vs-sell?item=" + ITEM + "&store=" + STORE + "&days=30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.lines.length()").value(7));

        mvc.perform(get("/api/v1/sell/speed-tiers?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers.length()").value(3));
    }

    @Test
    @DisplayName("POST /sell/quote: no write, breakdown + deal price")
    void quote() throws Exception {
        mvc.perform(post("/api/v1/sell/quote")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"customerId\":\"c-1\",\"qty\":240}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealPrice").isNumber())
                .andExpect(jsonPath("$.quote.qty").value(240))
                .andExpect(jsonPath("$.customerProfile.label").isNotEmpty());
    }

    @Test
    @DisplayName("GET /sell/atp: available-to-promise allocation")
    void atp() throws Exception {
        mvc.perform(get("/api/v1/sell/atp?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isNumber())
                .andExpect(jsonPath("$.lots.length()").value(3))
                .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    @DisplayName("GET /sell/starters: top three opportunities for the empty state")
    void starters() throws Exception {
        // Field names, not just shape: the frontend's SellStarter (platform/backend.ts) names
        // these item/store/pct, not itemNumber/storeId/upliftPct like every other sell DTO -
        // a drift here once shipped a `s.pct.toFixed is not a function` crash on the Sell tab.
        mvc.perform(get("/api/v1/sell/starters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].item").exists())
                .andExpect(jsonPath("$[0].store").exists())
                .andExpect(jsonPath("$[0].pct").exists());
    }

    @Test
    @DisplayName("POST /sell/apply then POST /sell/quotes: each writes a decision GET /sell/outcomes reads back")
    void applyThenQuoteThenOutcomes() throws Exception {
        mvc.perform(post("/api/v1/sell/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").isNotEmpty())
                .andExpect(jsonPath("$.decision.kind").value("sell"));

        mvc.perform(post("/api/v1/sell/quotes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"customerId\":\"c-3\",\"qty\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").isNotEmpty());

        mvc.perform(get("/api/v1/sell/outcomes?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    @DisplayName("an item with no sales history at a store is a 404 for the tool endpoints")
    void notPriceableLineIsNotFound() throws Exception {
        // HRD900001 is PIM-only (no sales anywhere in the seed).
        mvc.perform(get("/api/v1/sell/speed-tiers?item=HRD900001&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
